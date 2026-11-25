"""Những sự kiện mà một người gọi được phép phân tích.

ADMIN thấy tất cả. Người khác (ban tổ chức, bất kể vai trò hệ thống) chỉ thấy các sự kiện mà theo đúng quy tắc của
event-service họ là OWNER, ADMIN hoặc MANAGER và đang ACTIVE: tức có dòng staff_assignments của người dùng với trạng thái
ACTIVE và một trong các vai trò đó. Hồ sơ ban tổ chức (event_organizers) chỉ là tên hiển thị, không quyết định gì ở đây.
Thành viên CHECKIN_STAFF và REVOKED không nhận được gì.

Token mang id Keycloak; staff_assignments lưu id của user-service. user-service đổi cái này sang cái kia
(GET /api/users/me/ekyc/organizer-eligibility, gọi bằng token của chính người gọi nên chỉ trả lời cho người đó).
"""
import logging
import threading
import time
from dataclasses import dataclass
from typing import Optional

import httpx
from fastapi import Depends, HTTPException, Request
from sqlalchemy import text

from app.core.config import settings
from app.core.security import ADMIN_ROLES, require_login, roles_of
from app.db.session import SessionLocal
from app.services.ai.sql_guard import ScopeSpec, parse_allowed

log = logging.getLogger("ai.scope")

MANAGING_ROLES = ("OWNER", "ADMIN", "MANAGER")
_CACHE_SECONDS = 30
_cache: dict = {}
_cache_lock = threading.Lock()


class ScopeUnavailable(Exception):
    """Không xác định được các sự kiện của người gọi (user-service hoặc cơ sở dữ liệu không trả lời)."""


@dataclass(frozen=True)
class AnalysisScope:
    """Phạm vi phân tích: cờ admin và danh sách id sự kiện người gọi được quản lý."""
    is_admin: bool
    event_ids: tuple = ()

    @classmethod
    def admin(cls) -> "AnalysisScope":
        """Tạo phạm vi của admin: không giới hạn sự kiện."""
        return cls(True, ())

    def spec(self) -> Optional[ScopeSpec]:
        """Dựng ScopeSpec (cột lọc theo bảng, bảng công khai, danh sách sự kiện) cho bộ lọc SQL; None với admin vì không cần giới hạn."""
        if self.is_admin:
            return None
        columns = {}
        for item in settings.ai_scope_columns.split(","):
            relation, _, column = item.strip().partition(":")
            if relation and column:
                columns[relation.strip().lower()] = column.strip()
        return ScopeSpec(columns, parse_allowed(settings.ai_scope_public_relations), self.event_ids)

    def allows_event(self, event_id: str) -> bool:
        """Admin được phép mọi sự kiện; người khác chỉ khi event_id nằm trong danh sách của mình."""
        return self.is_admin or event_id in self.event_ids

    @property
    def session_value(self) -> str:
        """Giá trị đặt vào app.allowed_event_ids cho bộ lọc phía cơ sở dữ liệu của các view (03-ai-organizer-scope.sql)."""
        return "*" if self.is_admin else ",".join(self.event_ids)


def fetch_user_id(token: str) -> str:
    """Đổi token (id Keycloak) thành id user-service bằng cách gọi organizer-eligibility. Ném ScopeUnavailable khi lỗi hoặc không có userId."""
    try:
        response = httpx.get(
            settings.user_service_url.rstrip("/") + "/api/users/me/ekyc/organizer-eligibility",
            headers={"Authorization": f"Bearer {token}"},
            timeout=settings.user_service_timeout_seconds,
        )
        response.raise_for_status()
        user_id = response.json().get("userId")
    except Exception as failure:
        log.warning("Could not look up the user for the analysis scope: %s", type(failure).__name__)
        raise ScopeUnavailable() from failure
    if not user_id:
        raise ScopeUnavailable()
    return str(user_id)


def events_for_user(user_id: str) -> list:
    """Lấy id các sự kiện mà người dùng là OWNER, ADMIN hoặc MANAGER đang ACTIVE, đã sắp xếp. Ném ScopeUnavailable khi đọc cơ sở dữ liệu lỗi."""
    try:
        with SessionLocal() as db:
            rows = db.execute(text("""
                SELECT sa.event_id::text FROM staff_assignments sa
                 WHERE sa.user_id = :uid AND sa.status = 'ACTIVE' AND sa.role IN ('OWNER', 'ADMIN', 'MANAGER')
            """), {"uid": user_id}).fetchall()
            db.rollback()
    except Exception as failure:
        log.warning("Could not read the events of a user: %s", type(failure).__name__)
        raise ScopeUnavailable() from failure
    return sorted(row[0] for row in rows)


@dataclass(frozen=True)
class OrganizerChatIdentity:
    is_admin: bool
    user_id: str | None = None


def chat_events(identity: OrganizerChatIdentity, event_id: str | None, page_size: int = 100) -> list[dict]:
    """Read fresh permissions for each chat turn, with keyset paging in a single snapshot."""
    query = text("""
        WITH assignments AS (
            SELECT event_id, bool_or(role = 'OWNER') AS can_view_finance
              FROM staff_assignments
             WHERE user_id = :uid AND status = 'ACTIVE' AND role IN ('OWNER', 'ADMIN', 'MANAGER')
             GROUP BY event_id
        )
        SELECT e.id AS event_id, coalesce(nullif(e.title, ''), e.id) AS title, e.event_status AS status, e.start_time, e.end_time,
               (:admin OR COALESCE(a.can_view_finance, false)) AS can_view_finance
          FROM events e
          LEFT JOIN assignments a ON a.event_id = e.id
         WHERE (:admin OR a.event_id IS NOT NULL)
           AND (CAST(:event_id AS text) IS NULL OR e.id = :event_id)
           AND (CAST(:after AS text) IS NULL OR e.id > :after)
         ORDER BY e.id LIMIT :limit
    """)
    events = []
    with SessionLocal() as db:
        db.execute(text("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"))
        db.execute(text("SELECT set_config('statement_timeout', :timeout, true)"),
                   {"timeout": str(settings.ai_sql_timeout_ms)})
        after = None
        while True:
            rows = db.execute(query, {"uid": identity.user_id, "admin": identity.is_admin,
                                     "event_id": event_id, "after": after, "limit": page_size}).mappings().all()
            events.extend(dict(row) for row in rows)
            if len(rows) < page_size:
                return events
            after = rows[-1]["event_id"]


def organizer_chat_identity(request: Request, claims: dict) -> OrganizerChatIdentity:
    if roles_of(claims) & ADMIN_ROLES:
        return OrganizerChatIdentity(True)
    token = request.headers.get("Authorization", "").partition(" ")[2].strip()
    try:
        return OrganizerChatIdentity(False, fetch_user_id(token))
    except ScopeUnavailable:
        raise HTTPException(status_code=503, detail={"code": "SCOPE_UNAVAILABLE",
                                                    "message": "Chưa xác định được phạm vi dữ liệu của bạn."})


def resolve_scope(claims: dict, token: str) -> AnalysisScope:
    """Xác định phạm vi của người gọi: admin thấy tất cả, người khác được tra theo token và cache 30 giây theo `sub`."""
    if roles_of(claims) & ADMIN_ROLES:
        return AnalysisScope.admin()
    subject = str(claims.get("sub", ""))
    now = time.time()
    with _cache_lock:
        cached = _cache.get(subject)
        if cached and cached[0] > now:
            return cached[1]
    event_ids = events_for_user(fetch_user_id(token))[: settings.ai_scope_max_events]
    scope = AnalysisScope(False, tuple(event_ids))
    with _cache_lock:
        _cache[subject] = (now + _CACHE_SECONDS, scope)
    return scope


def clear_cache() -> None:
    """Xóa cache phạm vi (dùng khi kiểm thử)."""
    with _cache_lock:
        _cache.clear()


def analysis_scope(request: Request, claims: dict = Depends(require_login)) -> AnalysisScope:
    """Dependency của các route phân tích: xác định người gọi là ai và phạm vi sự kiện tương ứng. Trả HTTP 503 SCOPE_UNAVAILABLE khi không xác định được."""
    if roles_of(claims) & ADMIN_ROLES:
        return AnalysisScope.admin()
    token = request.headers.get("Authorization", "").partition(" ")[2].strip()
    try:
        return resolve_scope(claims, token)
    except ScopeUnavailable:
        raise HTTPException(status_code=503, detail={"code": "SCOPE_UNAVAILABLE", "message": "Chưa xác định được phạm vi dữ liệu của bạn. Vui lòng thử lại sau."})
