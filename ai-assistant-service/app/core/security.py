"""Xác thực access token của Keycloak trên các route cần đăng nhập hoặc quyền ADMIN.

Gateway đã kiểm tra token, nhưng dịch vụ này cũng truy cập được từ các container khác trong mạng Docker, nên các route
có thể chạy truy vấn hoặc đọc dữ liệu máy chủ không tin rằng người gọi đã đi qua gateway. Token được xác thực thật ở đây:
chữ ký (khóa lấy từ endpoint JWKS của Keycloak), issuer, thời hạn.
Các route chatbot công khai (`/api/customer/**`) không dùng các dependency này.
"""
import logging
from functools import lru_cache
from typing import Callable, Optional

import jwt
from fastapi import Depends, HTTPException, Request
from jwt import PyJWKClient

from app.core.config import settings

log = logging.getLogger("ai.auth")

ADMIN_ROLES = frozenset({"ADMIN", "ROLE_ADMIN"})


class TokenVerifier:
    """Bộ xác thực JWT RS256: kiểm tra chữ ký, issuer, thời hạn và (nếu có cấu hình) audience."""
    def __init__(self, issuer: str, key_resolver: Callable[[str], object], audience: str = "", leeway_seconds: int = 10):
        """Khởi tạo bộ xác thực với issuer, hàm tìm khóa công khai theo token, audience (rỗng = không kiểm tra) và độ lệch đồng hồ cho phép."""
        self.issuer = issuer
        self.audience = audience
        self.leeway_seconds = leeway_seconds
        self._key_resolver = key_resolver

    @classmethod
    def from_jwks(cls, issuer: str, jwks_uri: str, audience: str = "") -> "TokenVerifier":
        """Tạo bộ xác thực lấy khóa ký từ endpoint JWKS (lưu đệm khóa 1 giờ, timeout 5 giây)."""
        client = PyJWKClient(jwks_uri, cache_keys=True, lifespan=3600, timeout=5)
        return cls(issuer, lambda token: client.get_signing_key_from_jwt(token).key, audience)

    def verify(self, token: str) -> dict:
        """Trả về claims của token, hoặc ném `jwt.PyJWTError` (sai chữ ký, sai issuer, hết hạn, sai định dạng...)."""
        key = self._key_resolver(token)
        return jwt.decode(
            token,
            key,
            algorithms=["RS256"],  # không bao giờ chấp nhận "none" hay khóa bí mật dùng chung
            issuer=self.issuer,
            audience=self.audience or None,
            leeway=self.leeway_seconds,
            options={"require": ["exp", "iss"], "verify_aud": bool(self.audience)},
        )


@lru_cache(maxsize=1)
def get_verifier() -> Optional[TokenVerifier]:
    """Bộ xác thực dùng chung (lưu đệm); `None` nếu chưa cấu hình issuer hoặc JWKS của Keycloak."""
    if not settings.keycloak_issuer_uri or not settings.keycloak_jwk_set_uri:
        return None
    return TokenVerifier.from_jwks(settings.keycloak_issuer_uri, settings.keycloak_jwk_set_uri, settings.auth_audience)


def _reject(status: int, code: str, message: str) -> HTTPException:
    """Dựng `HTTPException` với detail `{code, message}`; lỗi 401 kèm header `WWW-Authenticate: Bearer`."""
    headers = {"WWW-Authenticate": "Bearer"} if status == 401 else None
    return HTTPException(status_code=status, detail={"code": code, "message": message}, headers=headers)


def roles_of(claims: dict) -> set:
    """Tập vai trò (viết hoa) lấy từ claim `roles` và `realm_access.roles`."""
    roles = set()
    direct = claims.get("roles")
    if isinstance(direct, (list, tuple)):
        roles.update(str(role).upper() for role in direct)
    realm = claims.get("realm_access")
    if isinstance(realm, dict) and isinstance(realm.get("roles"), (list, tuple)):
        roles.update(str(role).upper() for role in realm["roles"])
    return roles


def require_login(request: Request, verifier: Optional[TokenVerifier] = Depends(get_verifier)) -> dict:
    """Yêu cầu access token hợp lệ, vai trò nào cũng được; trả về claims. Lỗi 503 nếu chưa cấu hình xác thực, 401 nếu thiếu hoặc sai token."""
    if verifier is None:
        log.error("Token verification needs KEYCLOAK_ISSUER_URI / KEYCLOAK_JWK_SET_URI are not set; refusing the request")
        raise _reject(503, "AUTH_NOT_CONFIGURED", "Dịch vụ chưa được cấu hình xác thực.")
    header = request.headers.get("Authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise _reject(401, "UNAUTHENTICATED", "Cần đăng nhập.")
    try:
        claims = verifier.verify(token.strip())
    except Exception as failure:  # gồm PyJWTError và cả trường hợp không kết nối được Keycloak để lấy khóa
        log.warning("Token refused: %s", type(failure).__name__)
        raise _reject(401, "INVALID_TOKEN", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.")
    request.state.claims = claims  # app.core.ratelimit đọc lại để token chỉ được xác thực một lần mỗi yêu cầu
    return claims


def require_admin(claims: dict = Depends(require_login)) -> dict:
    """Yêu cầu access token hợp lệ có vai trò ADMIN (`ADMIN` hoặc `ROLE_ADMIN`); trả 403 nếu không phải quản trị viên."""
    if not (roles_of(claims) & ADMIN_ROLES):
        log.warning("Admin-only route refused for a non-admin (subject %s)", str(claims.get("sub", "?"))[:8])
        raise _reject(403, "ADMIN_ONLY", "Chỉ quản trị viên được phép sử dụng chức năng này.")
    return claims


def get_optional_claims(request: Request) -> Optional[dict]:
    """Claims của bearer token hợp lệ, hoặc `None` (không có header, token sai, chưa cấu hình xác thực). Không bao giờ ném ngoại lệ."""
    cached = getattr(request.state, "claims", None)
    if cached is not None:
        return cached
    header = request.headers.get("Authorization", "")
    if not header:
        return None
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    try:
        resolve = request.app.dependency_overrides.get(get_verifier, get_verifier)  # tôn trọng dependency override của test
        verifier = resolve()
        if verifier is None:
            return None
        claims = verifier.verify(token.strip())
    except Exception as failure:
        log.info("Optional token ignored: %s", type(failure).__name__)
        return None
    request.state.claims = claims
    return claims
