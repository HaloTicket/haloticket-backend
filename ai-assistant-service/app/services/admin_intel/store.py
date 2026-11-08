"""Storage in the ai_ops database through the role ai_admin_rw (haloticket-infrastructure/postgres/ai-admin).

Tables: event_risk_assessments (append only), event_risk_decisions, ai_admin_pins, ai_dashboard_summaries.
Without AI_ADMIN_DATABASE_URL, configured() is False and require_engine() raises AI_DB_NOT_CONFIGURED; callers that can
work without storage (risk scoring) check configured() first and answer with stored=false.
"""
import json
import logging
import threading
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import text

from app.core.config import settings
from app.core.errors import ai_error, AI_DB_NOT_CONFIGURED
from app.db.session import make_engine

log = logging.getLogger("ai.admin.store")

_lock = threading.Lock()
_cached = {"url": None, "engine": None}


def configured() -> bool:
    return bool(settings.ai_admin_database_url)


def require_engine():
    url = settings.ai_admin_database_url
    if not url:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    with _lock:
        if _cached["engine"] is None or _cached["url"] != url:
            if _cached["engine"] is not None:
                _cached["engine"].dispose()
            _cached["engine"] = make_engine(url, connect_args={"connect_timeout": 3, "options": "-c statement_timeout=5000"})
            _cached["url"] = url
        return _cached["engine"]


def reset_for_tests() -> None:
    with _lock:
        if _cached["engine"] is not None:
            _cached["engine"].dispose()
        _cached.update(url=None, engine=None)


def _iso(value) -> Optional[str]:
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=timezone.utc)).isoformat()
    return None


# assessments
_ASSESSMENT_COLUMNS = ("id, event_id, content_hash, ruleset_version, score, level, complete, reasons, coverage, ai_layer, "
                       "trigger, created_by, created_at")


def _assessment_row(row) -> dict:
    return {
        "assessment_id": row.id, "event_id": row.event_id, "content_hash": row.content_hash,
        "ruleset_version": row.ruleset_version, "risk_score": row.score, "risk_level": row.level, "complete": row.complete,
        "reason_items": row.reasons or [], "coverage": row.coverage or {}, "ai_layer": row.ai_layer, "trigger": row.trigger,
        "assessed_at": _iso(row.created_at),
    }


def latest_assessment(event_id: str) -> Optional[dict]:
    with require_engine().connect() as conn:
        row = conn.execute(text(f"SELECT {_ASSESSMENT_COLUMNS} FROM event_risk_assessments WHERE event_id = :e "
                                "ORDER BY created_at DESC, id DESC LIMIT 1"), {"e": event_id}).fetchone()
        conn.rollback()
    return _assessment_row(row) if row else None


def latest_assessments(event_ids: list) -> dict:
    if not event_ids:
        return {}
    with require_engine().connect() as conn:
        rows = conn.execute(text(f"SELECT DISTINCT ON (event_id) {_ASSESSMENT_COLUMNS} FROM event_risk_assessments "
                                 "WHERE event_id = ANY(:ids) ORDER BY event_id, created_at DESC, id DESC"),
                            {"ids": list(event_ids)}).fetchall()
        conn.rollback()
    return {row.event_id: _assessment_row(row) for row in rows}


def assessment_history(event_id: str, limit: int = 10) -> list:
    with require_engine().connect() as conn:
        rows = conn.execute(text(f"SELECT {_ASSESSMENT_COLUMNS} FROM event_risk_assessments WHERE event_id = :e "
                                 "ORDER BY created_at DESC, id DESC LIMIT :n"), {"e": event_id, "n": limit}).fetchall()
        conn.rollback()
    return [_assessment_row(r) for r in rows]


def save_assessment(event_id: str, result: dict, content_hash: str, trigger: str, created_by: str) -> int:
    with require_engine().begin() as conn:
        row = conn.execute(text(
            "INSERT INTO event_risk_assessments (event_id, content_hash, ruleset_version, score, level, complete, reasons, coverage, "
            "ai_layer, trigger, created_by) VALUES (:e, :h, :v, :s, :l, :c, CAST(:r AS jsonb), CAST(:cov AS jsonb), :ai, :t, :by) RETURNING id"),
            {"e": event_id, "h": content_hash, "v": result.get("ruleset_version") or "", "s": result["risk_score"],
             "l": result["risk_level"], "c": result["complete"], "r": json.dumps(result["reason_items"], ensure_ascii=False),
             "cov": json.dumps(result["coverage"], ensure_ascii=False), "ai": result["coverage"]["ai"], "t": trigger, "by": created_by[:80]}).fetchone()
    return int(row.id)


# decisions
def save_decision(event_id: str, decision: str, reason: str, decided_by: Optional[str], source: str) -> dict:
    with require_engine().begin() as conn:
        assessment = conn.execute(text("SELECT id FROM event_risk_assessments WHERE event_id = :e ORDER BY created_at DESC, id DESC LIMIT 1"),
                                  {"e": event_id}).fetchone()
        existing = conn.execute(text("SELECT id FROM event_risk_decisions WHERE event_id = :e AND decision = :d AND source = :s "
                                     "AND decided_at > now() - interval '10 minutes' LIMIT 1"), {"e": event_id, "d": decision, "s": source}).fetchone()
        if existing:
            return {"id": existing.id, "duplicate": True}
        row = conn.execute(text("INSERT INTO event_risk_decisions (event_id, assessment_id, decision, reason, decided_by, source) "
                                "VALUES (:e, :a, :d, :r, :by, :s) RETURNING id"),
                           {"e": event_id, "a": assessment.id if assessment else None, "d": decision, "r": (reason or "")[:2000],
                            "by": (decided_by or None), "s": source}).fetchone()
    return {"id": int(row.id), "duplicate": False}


def decisions_since(days: int) -> list:
    since = datetime.now(timezone.utc) - timedelta(days=days)
    with require_engine().connect() as conn:
        rows = conn.execute(text("SELECT DISTINCT ON (event_id) event_id, decision, source FROM event_risk_decisions "
                                 "WHERE decided_at >= :since ORDER BY event_id, decided_at DESC"), {"since": since}).fetchall()
        conn.rollback()
    return [{"event_id": r.event_id, "decision": r.decision, "source": r.source} for r in rows]


def assessed_since(days: int) -> list:
    """Latest assessment (level, id) per event assessed in the window."""
    since = datetime.now(timezone.utc) - timedelta(days=days)
    with require_engine().connect() as conn:
        rows = conn.execute(text("SELECT DISTINCT ON (event_id) event_id, level, complete FROM event_risk_assessments "
                                 "WHERE created_at >= :since ORDER BY event_id, created_at DESC, id DESC"), {"since": since}).fetchall()
        conn.rollback()
    return [{"event_id": r.event_id, "level": r.level, "complete": r.complete} for r in rows]


# pins
def count_pins(admin: str) -> int:
    with require_engine().connect() as conn:
        n = conn.execute(text("SELECT count(*) FROM ai_admin_pins WHERE admin_sub = :a"), {"a": admin}).scalar()
        conn.rollback()
    return int(n or 0)


def list_pins(admin: str) -> list:
    with require_engine().connect() as conn:
        rows = conn.execute(text("SELECT pin_id, title, question, sql_text, chart_type, snapshot, created_at FROM ai_admin_pins "
                                 "WHERE admin_sub = :a ORDER BY created_at DESC"), {"a": admin}).fetchall()
        conn.rollback()
    return [{"pin_id": r.pin_id, "title": r.title, "question": r.question, "sql": r.sql_text, "chart_type": r.chart_type,
             "snapshot": r.snapshot, "created_at": _iso(r.created_at)} for r in rows]


def create_pin(admin: str, title: str, question: str, sql_text: str, chart_type: str, snapshot) -> str:
    pin_id = uuid.uuid4().hex
    with require_engine().begin() as conn:
        conn.execute(text("INSERT INTO ai_admin_pins (pin_id, admin_sub, title, question, sql_text, chart_type, snapshot) "
                          "VALUES (:p, :a, :t, :q, :s, :c, CAST(:snap AS jsonb))"),
                     {"p": pin_id, "a": admin, "t": title[:200], "q": question[:1000], "s": sql_text, "c": chart_type[:30],
                      "snap": json.dumps(snapshot, ensure_ascii=False, default=str)})
    return pin_id


def update_pin_snapshot(admin: str, pin_id: str, snapshot) -> None:
    with require_engine().begin() as conn:
        conn.execute(text("UPDATE ai_admin_pins SET snapshot = CAST(:snap AS jsonb) WHERE pin_id = :p AND admin_sub = :a"),
                     {"snap": json.dumps(snapshot, ensure_ascii=False, default=str), "p": pin_id, "a": admin})


def get_pin(admin: str, pin_id: str) -> Optional[dict]:
    return next((p for p in list_pins(admin) if p["pin_id"] == pin_id), None)


def delete_pin(admin: str, pin_id: str) -> bool:
    with require_engine().begin() as conn:
        result = conn.execute(text("DELETE FROM ai_admin_pins WHERE pin_id = :p AND admin_sub = :a"), {"p": pin_id, "a": admin})
    return result.rowcount > 0


# dashboard summaries
def get_summary(day: str, lang: str) -> Optional[dict]:
    with require_engine().connect() as conn:
        row = conn.execute(text("SELECT summary, kpi_hash, created_at FROM ai_dashboard_summaries WHERE day = :d AND lang = :l"),
                           {"d": day, "l": lang}).fetchone()
        conn.rollback()
    return {"summary": row.summary, "kpi_hash": row.kpi_hash, "created_at": _iso(row.created_at)} if row else None


def put_summary(day: str, lang: str, summary: str, kpi_hash: str) -> None:
    with require_engine().begin() as conn:
        conn.execute(text("INSERT INTO ai_dashboard_summaries (day, lang, summary, kpi_hash) VALUES (:d, :l, :s, :h) "
                          "ON CONFLICT (day, lang) DO UPDATE SET summary = EXCLUDED.summary, kpi_hash = EXCLUDED.kpi_hash, created_at = now()"),
                     {"d": day, "l": lang, "s": summary, "h": kpi_hash})
