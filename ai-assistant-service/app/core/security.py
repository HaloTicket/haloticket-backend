"""Verifies the Keycloak access token on the routes that need a login or the ADMIN role.

The gateway already checks tokens, but this service is also reachable from other containers in the Docker network, so
the routes that can run queries or read the server do not trust the caller to have passed the gateway. The token is
verified here for real: signature (keys fetched from Keycloak's JWKS endpoint), issuer, expiry.
The public chatbot routes (/api/customer/**) do not use these dependencies.
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
    def __init__(self, issuer: str, key_resolver: Callable[[str], object], audience: str = "", leeway_seconds: int = 10):
        self.issuer = issuer
        self.audience = audience
        self.leeway_seconds = leeway_seconds
        self._key_resolver = key_resolver

    @classmethod
    def from_jwks(cls, issuer: str, jwks_uri: str, audience: str = "") -> "TokenVerifier":
        client = PyJWKClient(jwks_uri, cache_keys=True, lifespan=3600, timeout=5)
        return cls(issuer, lambda token: client.get_signing_key_from_jwt(token).key, audience)

    def verify(self, token: str) -> dict:
        """Returns the claims, or raises jwt.PyJWTError (bad signature, wrong issuer, expired, malformed...)."""
        key = self._key_resolver(token)
        return jwt.decode(
            token,
            key,
            algorithms=["RS256"],  # never "none", never a shared secret
            issuer=self.issuer,
            audience=self.audience or None,
            leeway=self.leeway_seconds,
            options={"require": ["exp", "iss"], "verify_aud": bool(self.audience)},
        )


@lru_cache(maxsize=1)
def get_verifier() -> Optional[TokenVerifier]:
    if not settings.keycloak_issuer_uri or not settings.keycloak_jwk_set_uri:
        return None
    return TokenVerifier.from_jwks(settings.keycloak_issuer_uri, settings.keycloak_jwk_set_uri, settings.auth_audience)


def _reject(status: int, code: str, message: str) -> HTTPException:
    headers = {"WWW-Authenticate": "Bearer"} if status == 401 else None
    return HTTPException(status_code=status, detail={"code": code, "message": message}, headers=headers)


def roles_of(claims: dict) -> set:
    roles = set()
    direct = claims.get("roles")
    if isinstance(direct, (list, tuple)):
        roles.update(str(role).upper() for role in direct)
    realm = claims.get("realm_access")
    if isinstance(realm, dict) and isinstance(realm.get("roles"), (list, tuple)):
        roles.update(str(role).upper() for role in realm["roles"])
    return roles


def require_login(request: Request, verifier: Optional[TokenVerifier] = Depends(get_verifier)) -> dict:
    """A valid access token, any role. Returns the claims."""
    if not settings.ai_auth_enforced:
        return {}
    if verifier is None:
        log.error("Token verification is on but KEYCLOAK_ISSUER_URI / KEYCLOAK_JWK_SET_URI are not set; refusing the request")
        raise _reject(503, "AUTH_NOT_CONFIGURED", "Dịch vụ chưa được cấu hình xác thực.")
    header = request.headers.get("Authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise _reject(401, "UNAUTHENTICATED", "Cần đăng nhập.")
    try:
        return verifier.verify(token.strip())
    except Exception as failure:  # PyJWTError, but also a Keycloak that cannot be reached for the keys
        log.warning("Token refused: %s", type(failure).__name__)
        raise _reject(401, "INVALID_TOKEN", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.")


def require_admin(claims: dict = Depends(require_login)) -> dict:
    """A valid access token that carries ROLE_ADMIN."""
    if not settings.ai_auth_enforced:
        return claims
    if not (roles_of(claims) & ADMIN_ROLES):
        log.warning("Admin-only route refused for a non-admin (subject %s)", str(claims.get("sub", "?"))[:8])
        raise _reject(403, "ADMIN_ONLY", "Chỉ quản trị viên được phép sử dụng chức năng này.")
    return claims
