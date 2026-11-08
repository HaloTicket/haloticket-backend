"""Admin intelligence routes, mounted under /api/admin by admin.py (so require_admin and the gateway adminOnly rule apply):
multi-layer risk scoring, approval decisions, dashboard KPIs and summary, attention feed, pinned reports, assistant with tools.
"""
import asyncio
import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.core.config import settings
from app.core.errors import ai_error, AI_INPUT_INVALID, AI_DB_NOT_CONFIGURED
from app.core.ratelimit import actor_of, rate_limit, check as rate_check
from app.services.admin_intel import assistant, kpis as kpi_module, risk_service, store, summary, upstream
from app.services.ai import ai_call

log = logging.getLogger("ai.admin.intel")

router = APIRouter()

MAX_EVENT_ID = 100
MAX_BATCH = 100
MAX_PIN_SQL = 8000
MAX_SNAPSHOT_ROWS = 50
KPI_CACHE_FACTOR = 2


def _actor(request: Request) -> str:
    return actor_of(request, getattr(request.state, "claims", None))


def _admin_sub(request: Request) -> str:
    sub = str((getattr(request.state, "claims", None) or {}).get("sub") or "")
    if not sub:
        raise ai_error(AI_INPUT_INVALID)
    return sub


def _event_id(value: str) -> str:
    value = (value or "").strip()
    if not value or len(value) > MAX_EVENT_ID:
        raise ai_error(AI_INPUT_INVALID)
    return value


async def _health() -> list:
    from app.api.endpoints import admin
    try:
        return await admin.get_system_health()
    except Exception as failure:
        log.warning("System health unavailable for the dashboard: %s", type(failure).__name__)
        return []


# risk scoring
class RiskScoringRequest(BaseModel):
    event_id: str
    title: str = ""
    description: str = ""
    organizer_name: str = ""
    refresh: bool = False


def _risk_response(result: dict) -> dict:
    return {k: result.get(k) for k in (
        "risk_score", "risk_level", "reasons", "reason_items", "ruleset_version", "assessed_at", "content_hash", "coverage",
        "complete", "cached", "stored", "assessment_id", "ai_layer", "event_id")}


@router.post("/risk-scoring")
def analyze_risk_score(body: RiskScoringRequest, http: Request):
    """Three-layer score. The event is read from the event service; title/description/organizer_name are used only when it
    cannot be read. A stored score for the same content is returned without calling the model unless refresh=true."""
    event_id = _event_id(body.event_id)
    actor = _actor(http)
    result = risk_service.assess(
        upstream.token_of(http), actor, event_id,
        fallback={"title": body.title, "description": body.description, "organizer_name": body.organizer_name},
        refresh=body.refresh, run_ai=True, trigger="manual" if body.refresh else "auto",
        check_limit=risk_service.ai_limit_checker(actor),
    )
    return _risk_response(result)


class RiskBatchRequest(BaseModel):
    event_ids: list[str] = Field(default_factory=list)


@router.post("/risk/batch")
def risk_batch(body: RiskBatchRequest, http: Request):
    """Layer A+B scores (no model call) for the review queue. Unknown or unreadable events are listed in `missing`."""
    ids = list(dict.fromkeys(_event_id(i) for i in body.event_ids))[:MAX_BATCH]
    authorization = upstream.token_of(http)
    try:
        events = {str(e.get("id")): e for e in upstream.approvals(authorization)}
    except Exception as failure:
        log.warning("Queue assessment: events unreadable: %s", type(failure).__name__)
        raise HTTPException(status_code=502, detail={"code": "UPSTREAM_UNAVAILABLE", "message": "Không đọc được danh sách sự kiện."})
    found = [events[i] for i in ids if i in events]
    assessed = risk_service.ensure_assessments(authorization, _actor(http), found)
    return {"assessments": {i: _risk_response(a) for i, a in assessed.items()},
            "missing": [i for i in ids if i not in assessed]}


class DecisionRequest(BaseModel):
    event_id: str
    decision: str
    reason: str = ""


@router.post("/risk/decisions")
def record_decision(body: DecisionRequest, http: Request):
    """Called by the web after an approve/reject succeeded. The event's real status must match, so a decision that did not
    happen cannot be recorded. The approver is the token subject."""
    event_id = _event_id(body.event_id)
    decision = body.decision.strip().upper()
    if decision not in ("APPROVED", "REJECTED"):
        raise ai_error(AI_INPUT_INVALID)
    store.require_engine()
    try:
        events = upstream.approvals(upstream.token_of(http), fresh=True)
    except Exception:
        raise HTTPException(status_code=502, detail={"code": "UPSTREAM_UNAVAILABLE", "message": "Không đọc được trạng thái sự kiện."})
    current = next((e.get("eventApprovalStatus") for e in events if str(e.get("id")) == event_id), None)
    if current != decision:
        raise HTTPException(status_code=409, detail={"code": "DECISION_MISMATCH", "message": "Trạng thái thật của sự kiện không khớp với quyết định."})
    saved = store.save_decision(event_id, decision, body.reason, _admin_sub(http), "web")
    return {"recorded": True, "duplicate": saved["duplicate"]}


@router.get("/risk/agreement")
def risk_agreement(http: Request, days: int = 30):
    """How often the suggestion matched the decision. HIGH expects REJECTED, LOW expects APPROVED, MEDIUM is neutral.
    A decision with no web record is derived from the event's current status (no approver, no exact time)."""
    days = max(1, min(365, days))
    store.require_engine()
    assessed = store.assessed_since(days)
    recorded = {d["event_id"]: d["decision"] for d in store.decisions_since(days)}
    try:
        current = {str(e.get("id")): e.get("eventApprovalStatus") for e in upstream.approvals(upstream.token_of(http))}
    except Exception:
        current = {}
    out = {"days": days, "decided": 0, "agree": 0, "disagree": 0, "neutral": 0, "derived": 0, "pending": 0,
           "by_level": {"LOW": {"APPROVED": 0, "REJECTED": 0}, "MEDIUM": {"APPROVED": 0, "REJECTED": 0}, "HIGH": {"APPROVED": 0, "REJECTED": 0}}}
    for item in assessed:
        decision = recorded.get(item["event_id"])
        derived = False
        if decision is None and current.get(item["event_id"]) in ("APPROVED", "REJECTED"):
            decision, derived = current[item["event_id"]], True
        if decision is None:
            out["pending"] += 1
            continue
        out["decided"] += 1
        out["derived"] += 1 if derived else 0
        out["by_level"][item["level"]][decision] += 1
        if item["level"] == "MEDIUM":
            out["neutral"] += 1
        elif (item["level"] == "HIGH") == (decision == "REJECTED"):
            out["agree"] += 1
        else:
            out["disagree"] += 1
    return out


# dashboard
@router.get("/dashboard/kpis")
async def dashboard_kpis(http: Request):
    authorization, actor = upstream.token_of(http), _actor(http)
    health = await _health()
    return await asyncio.to_thread(
        upstream.cached, "kpis", settings.admin_cache_seconds * KPI_CACHE_FACTOR,
        lambda: kpi_module.build_kpis(authorization, actor, health))


@router.get("/attention")
async def attention(http: Request):
    authorization, actor = upstream.token_of(http), _actor(http)
    health = await _health()
    return await asyncio.to_thread(
        upstream.cached, "attention", settings.admin_cache_seconds,
        lambda: kpi_module.build_attention(authorization, actor, health))


class SummaryRequest(BaseModel):
    lang: str = "vi"
    refresh: bool = False


@router.post("/dashboard/summary")
async def dashboard_summary(body: SummaryRequest, http: Request):
    """The only place the model is used on the dashboard, and only when the admin asks. Cached per day and language."""
    lang = body.lang if body.lang in summary.LANGS else "vi"
    actor = _actor(http)
    if not body.refresh:
        hit = await asyncio.to_thread(summary.cached, lang)
        if hit:
            return {"summary": hit["summary"], "reason": None, "cached": True, "created_at": hit["created_at"]}
    rate_check("dashboard_summary", actor, True)
    authorization = upstream.token_of(http)
    health = await _health()
    data = await asyncio.to_thread(kpi_module.build_kpis, authorization, actor, health)
    result = await asyncio.to_thread(summary.generate, data, lang, ai_call.ai_invoke, actor)
    if result["summary"] and store.configured():
        try:
            await asyncio.to_thread(store.put_summary, summary.today(), lang, result["summary"], data["hash"])
        except Exception as failure:
            log.warning("Summary not cached: %s", type(failure).__name__)
    return {**result, "cached": False, "created_at": data["generated_at"]}


# pinned reports
class PinRequest(BaseModel):
    title: str = Field(..., max_length=200)
    question: str = Field("", max_length=1000)
    sql: str = Field(..., max_length=MAX_PIN_SQL)
    chart_type: str = Field("table", max_length=30)
    snapshot: Optional[list] = None


def _engine_and_guard(sql: str):
    from app.services.ai import analysis_service
    from app.services.ai.sql_guard import SqlGuardError, validate_sql
    engine = analysis_service.engine_for("analysis")
    engine.require_account()
    try:
        guarded = validate_sql(sql, engine.allowed_relations, schema=engine.schema or None, max_rows=settings.ai_sql_max_rows, scope=None)
    except SqlGuardError:
        raise ai_error(AI_INPUT_INVALID)
    return engine, guarded


@router.get("/pins")
def list_pins(http: Request):
    return {"pins": store.list_pins(_admin_sub(http)), "max": settings.ai_pins_max_per_admin}


@router.post("/pins")
def create_pin(body: PinRequest, http: Request):
    admin = _admin_sub(http)
    _engine_and_guard(body.sql)  # the SQL is checked again here and again each time it runs; the client is never trusted
    if store.count_pins(admin) >= settings.ai_pins_max_per_admin:
        raise HTTPException(status_code=409, detail={"code": "PIN_LIMIT", "message": f"Mỗi quản trị viên chỉ ghim tối đa {settings.ai_pins_max_per_admin} báo cáo."})
    snapshot = (body.snapshot or [])[:MAX_SNAPSHOT_ROWS]
    return {"pin_id": store.create_pin(admin, body.title, body.question, body.sql, body.chart_type, snapshot)}


@router.get("/pins/{pin_id}/data")
def pin_data(pin_id: str, http: Request):
    """Runs the pinned query again (no model). When that fails the stored snapshot is returned and marked as old."""
    admin = _admin_sub(http)
    pin = store.get_pin(admin, pin_id)
    if pin is None:
        raise HTTPException(status_code=404, detail={"code": "PIN_NOT_FOUND", "message": "Không tìm thấy báo cáo đã ghim."})
    try:
        engine, guarded = _engine_and_guard(pin["sql"])
        rows = engine.run_readonly(guarded, "*")[:MAX_SNAPSHOT_ROWS]
        try:
            store.update_pin_snapshot(admin, pin_id, rows)
        except Exception as failure:
            log.warning("Pin snapshot not refreshed: %s", type(failure).__name__)
        return {"pin_id": pin_id, "title": pin["title"], "chart_type": pin["chart_type"], "rows": rows, "live": True}
    except Exception as failure:
        log.warning("Pinned query failed: %s", type(failure).__name__)
        return {"pin_id": pin_id, "title": pin["title"], "chart_type": pin["chart_type"], "rows": pin["snapshot"] or [],
                "live": False, "snapshot_at": pin["created_at"]}


@router.delete("/pins/{pin_id}")
def delete_pin(pin_id: str, http: Request):
    if not store.delete_pin(_admin_sub(http), pin_id):
        raise HTTPException(status_code=404, detail={"code": "PIN_NOT_FOUND", "message": "Không tìm thấy báo cáo đã ghim."})
    return {"deleted": True}


# assistant
MAX_CHAT_MESSAGE_CHARS = 4000


class AdminChatRequest(BaseModel):
    message: str
    history: list[dict] = []


class AdminChatResponse(BaseModel):
    reply: str
    tools_used: list[str] = []
    grounded: bool = True


@router.post("/assistant-chat", response_model=AdminChatResponse)
def chat_admin_assistant(request: AdminChatRequest, http: Request, _limit: None = Depends(rate_limit("admin_assistant"))):
    """Assistant with read-only tools. Same path and {reply} field as before; tools_used and grounded are new."""
    if not request.message.strip() or len(request.message) > MAX_CHAT_MESSAGE_CHARS:
        raise ai_error(AI_INPUT_INVALID)
    ctx = assistant.AssistantContext(authorization=upstream.token_of(http), actor=_actor(http), health=assistant.default_health)
    return AdminChatResponse(**assistant.run(request.message, request.history, ctx, ai_call.ai_invoke))
