"""Which events a caller may analyse.

An ADMIN sees everything. Anybody else (organizers, whatever their system role) sees only the events where, in
event-service's own rules, they are OWNER, ADMIN or MANAGER and ACTIVE:
  - a staff_assignments row of the user with status ACTIVE and one of those roles, or
  - the event's organizer profile belongs to the user and the user has no assignment row at all (older events, the same
    fallback EventAccessService applies).
CHECKIN_STAFF and REVOKED members get nothing.

The token carries the Keycloak id; staff_assignments holds the user-service id. user-service turns one into the other
(GET /api/users/me/ekyc/organizer-eligibility, called with the caller's own token, so it answers only for that caller).
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
    """The caller's events could not be worked out (user-service or the database did not answer)."""


@dataclass(frozen=True)
class AnalysisScope:
    is_admin: bool
    event_ids: tuple = ()

    @classmethod
    def admin(cls) -> "AnalysisScope":
        return cls(True, ())

    def spec(self) -> Optional[ScopeSpec]:
        """None for an admin (nothing to limit)."""
        if self.is_admin:
            return None
        columns = {}
        for item in settings.ai_scope_columns.split(","):
            relation, _, column = item.strip().partition(":")
            if relation and column:
                columns[relation.strip().lower()] = column.strip()
        return ScopeSpec(columns, parse_allowed(settings.ai_scope_public_relations), self.event_ids)

    def allows_event(self, event_id: str) -> bool:
        return self.is_admin or event_id in self.event_ids

    @property
    def session_value(self) -> str:
        """What goes into app.allowed_event_ids for the database-side filter of the views (03-ai-organizer-scope.sql)."""
        return "*" if self.is_admin else ",".join(self.event_ids)


def fetch_user_id(token: str) -> str:
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
    try:
        with SessionLocal() as db:
            rows = db.execute(text("""
                SELECT sa.event_id::text FROM staff_assignments sa
                 WHERE sa.user_id = :uid AND sa.status = 'ACTIVE' AND sa.role IN ('OWNER', 'ADMIN', 'MANAGER')
                UNION
                SELECT m.event_id::text FROM event_organizer_mapping m
                  JOIN event_organizers o ON o.id = m.organizer_id
                 WHERE o.user_id = :uid
                   AND NOT EXISTS (SELECT 1 FROM staff_assignments s WHERE s.event_id = m.event_id AND s.user_id = :uid)
            """), {"uid": user_id}).fetchall()
            db.rollback()
    except Exception as failure:
        log.warning("Could not read the events of a user: %s", type(failure).__name__)
        raise ScopeUnavailable() from failure
    return sorted(row[0] for row in rows)


def resolve_scope(claims: dict, token: str) -> AnalysisScope:
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
    with _cache_lock:
        _cache.clear()


def analysis_scope(request: Request, claims: dict = Depends(require_login)) -> AnalysisScope:
    """Dependency of the analysis routes: who the caller is and which events that covers."""
    if not settings.ai_auth_enforced or roles_of(claims) & ADMIN_ROLES:
        return AnalysisScope.admin()
    if not settings.ai_analysis_organizer_enabled:
        raise HTTPException(status_code=403, detail={"code": "ORGANIZER_ANALYSIS_DISABLED", "message": "Tính năng đang được nâng cấp."})
    token = request.headers.get("Authorization", "").partition(" ")[2].strip()
    try:
        return resolve_scope(claims, token)
    except ScopeUnavailable:
        raise HTTPException(status_code=503, detail={"code": "SCOPE_UNAVAILABLE", "message": "Chưa xác định được phạm vi dữ liệu của bạn. Vui lòng thử lại sau."})
