"""Readers for the data the admin intelligence features need. Everything goes through the gateway with the admin's OWN
Authorization header (no extra token, no extra database grant): the services decide what an admin may read.

Each reader raises on any failure; callers turn that into "not assessed" / null (never into a made-up value).
Small in-process caches keep the heavy, unpaged reads from being repeated on every page open.
"""
import logging
import threading
import time
from typing import Any, Optional

import httpx
from sqlalchemy import text

from app.core.config import settings

log = logging.getLogger("ai.admin.upstream")

APPROVALS_TTL = 30
USER_TTL = 300
PRICES_TTL = 300

_lock = threading.Lock()
_cache: dict = {}


def reset_for_tests() -> None:
    with _lock:
        _cache.clear()


def _cached(key: str, ttl: int, loader):
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
    value = loader()
    with _lock:
        _cache[key] = (time.time(), value)
        if len(_cache) > 2000:
            for stale in sorted(_cache, key=lambda k: _cache[k][0])[:500]:
                _cache.pop(stale, None)
    return value


cached = _cached  # public name for the other admin_intel modules


def token_of(request) -> str:
    """The raw Authorization header of the request (already verified by require_admin)."""
    return request.headers.get("Authorization", "")


def get_json(authorization: str, path: str, params: Optional[dict] = None) -> Any:
    url = settings.internal_api_base_url.rstrip("/") + path
    response = httpx.get(url, params=params, headers={"Authorization": authorization, "Accept": "application/json"},
                         timeout=settings.internal_api_timeout_seconds)
    if response.status_code != 200:
        raise RuntimeError(f"upstream {path} -> {response.status_code}")
    return response.json()


def approvals(authorization: str, fresh: bool = False) -> list:
    """Every event with its approval status (EventResponse-shaped dicts). The endpoint is not paged."""
    key = "approvals"
    if fresh:
        with _lock:
            _cache.pop(key, None)

    def load():
        data = get_json(authorization, "/api/events/approvals", {"status": "ALL"})
        if not isinstance(data, list):
            raise RuntimeError("approvals is not a list")
        return [e for e in data if isinstance(e, dict)]

    return _cached(key, APPROVALS_TTL, load)


def user(authorization: str, user_id: str) -> dict:
    data = _cached("user:" + user_id, USER_TTL, lambda: get_json(authorization, f"/api/users/admin/{user_id}"))
    if not isinstance(data, dict):
        raise RuntimeError("user is not an object")
    return data


def category_prices(category: str) -> list:
    """min_price of the APPROVED events of a category, read through the analysis role from ai_analytics.v_events."""
    from app.services.ai import analysis_service  # lazy: needs the analysis account configured

    def load():
        engine = analysis_service.engine_for("analysis")
        engine.require_account()
        with engine.engine.connect() as conn:
            try:
                conn.execute(text("SET TRANSACTION READ ONLY"))
                conn.execute(text("SET LOCAL statement_timeout = 3000"))
                conn.execute(text("SELECT set_config('app.allowed_event_ids', '*', true)"))
                rows = conn.execute(text("SELECT min_price FROM v_events WHERE approval_status = 'APPROVED' "
                                         "AND category_name = :c AND min_price IS NOT NULL LIMIT 500"), {"c": category}).fetchall()
            finally:
                conn.rollback()
        return [float(r[0]) for r in rows]

    return _cached("prices:" + category, PRICES_TTL, load)


def reconciliation_summary(authorization: str) -> dict:
    return get_json(authorization, "/api/bookings/admin/reconciliation/summary")


def reconciliation_exceptions(authorization: str) -> dict:
    return get_json(authorization, "/api/bookings/admin/reconciliation/exceptions", {"status": "OPEN", "page": 0, "size": 100})


def paid_without_tickets(authorization: str) -> dict:
    return get_json(authorization, "/api/bookings/admin/reconciliation/paid-without-tickets")


def sales_timeline(authorization: str, date_from: str, date_to: str) -> dict:
    # the report accepts only page sizes 10, 20 and 50 (any other value is a 400); only its daily timeline is used here
    return get_json(authorization, "/api/events/admin/reports", {"from": date_from, "to": date_to, "page": 1, "size": 10})


def resale_summary(authorization: str) -> dict:
    return get_json(authorization, "/api/marketplace/admin/listings/summary")
