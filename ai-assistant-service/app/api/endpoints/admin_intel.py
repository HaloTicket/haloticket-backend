"""Các route thông minh cho quản trị, gắn dưới `/api/admin` bởi admin.py (nên áp dụng `require_admin` và quy tắc adminOnly của gateway):
chấm điểm rủi ro nhiều lớp, quyết định duyệt, KPI và tóm tắt bảng điều khiển, danh sách cần chú ý, báo cáo đã ghim, trợ lý có công cụ.
"""
import asyncio
import hashlib
import json
import logging
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field
from pymongo.errors import DuplicateKeyError

from app.core.config import settings
from app.core.errors import AiError, ai_error, AI_INPUT_INVALID, AI_DB_NOT_CONFIGURED
from app.core.ratelimit import actor_of, rate_limit, check as rate_check
from app.services.admin_intel import assistant, kpis as kpi_module, risk_service, store, summary, upstream
from app.services.ai import ai_call
from app.services.ai.organizer_chat_store import ConversationChanged, organizer_chat_store

log = logging.getLogger("ai.admin.intel")

router = APIRouter()

MAX_EVENT_ID = 100
MAX_BATCH = 100
MAX_PIN_SQL = 8000
MAX_SNAPSHOT_ROWS = 50
KPI_CACHE_FACTOR = 2


def _actor(request: Request) -> str:
    """Định danh người gọi (từ claims của token) dùng cho giới hạn tần suất và nhật ký."""
    return actor_of(request, getattr(request.state, "claims", None))


def _admin_sub(request: Request) -> str:
    """Lấy `sub` của quản trị viên từ claims; ném lỗi `AI_INPUT_INVALID` nếu thiếu."""
    sub = str((getattr(request.state, "claims", None) or {}).get("sub") or "")
    if not sub:
        raise ai_error(AI_INPUT_INVALID)
    return sub


def _event_id(value: str) -> str:
    """Chuẩn hóa mã sự kiện (cắt khoảng trắng); ném lỗi `AI_INPUT_INVALID` nếu rỗng hoặc quá dài."""
    value = (value or "").strip()
    if not value or len(value) > MAX_EVENT_ID:
        raise ai_error(AI_INPUT_INVALID)
    return value


async def _health() -> list:
    """Lấy sức khỏe hệ thống cho bảng điều khiển; lỗi thì trả danh sách rỗng thay vì làm hỏng cả trang."""
    from app.api.endpoints import admin
    try:
        return await admin.get_system_health()
    except Exception as failure:
        log.warning("System health unavailable for the dashboard: %s", type(failure).__name__)
        return []


# Chấm điểm rủi ro
class RiskScoringRequest(BaseModel):
    """Yêu cầu chấm điểm rủi ro một sự kiện; các trường nội dung chỉ dùng khi không đọc được sự kiện từ event service."""
    event_id: str
    title: str = ""
    description: str = ""
    organizer_name: str = ""
    refresh: bool = False


def _risk_response(result: dict) -> dict:
    """Chọn đúng các trường kết quả rủi ro được phép trả cho client."""
    return {k: result.get(k) for k in (
        "risk_score", "risk_level", "reasons", "reason_items", "ruleset_version", "assessed_at", "content_hash", "coverage",
        "complete", "cached", "stored", "assessment_id", "ai_layer", "event_id")}


@router.post("/risk-scoring")
def analyze_risk_score(body: RiskScoringRequest, http: Request):
    """Chấm điểm ba lớp. Sự kiện được đọc từ event service; `title`, `description`, `organizer_name` chỉ dùng khi không đọc được.
    Điểm đã lưu cho cùng nội dung được trả về mà không gọi mô hình, trừ khi `refresh=true`."""
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
    """Danh sách mã sự kiện cần chấm điểm hàng loạt cho hàng đợi duyệt."""
    event_ids: list[str] = Field(default_factory=list)


@router.post("/risk/batch")
def risk_batch(body: RiskBatchRequest, http: Request):
    """Điểm lớp A+B (không gọi mô hình) cho hàng đợi duyệt. Sự kiện không tồn tại hoặc không đọc được được liệt kê trong `missing`."""
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
    """Quyết định duyệt (`APPROVED` hoặc `REJECTED`) kèm lý do mà web gửi sau khi đã duyệt hoặc từ chối sự kiện."""
    event_id: str
    decision: str
    reason: str = ""


@router.post("/risk/decisions")
def record_decision(body: DecisionRequest, http: Request):
    """Web gọi sau khi duyệt hoặc từ chối thành công. Trạng thái thật của sự kiện phải khớp, nên không thể ghi một quyết định chưa xảy ra.
    Người duyệt là chủ thể (`sub`) của token."""
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
    """Mức độ gợi ý khớp với quyết định thực tế. HIGH kỳ vọng REJECTED, LOW kỳ vọng APPROVED, MEDIUM là trung lập.
    Quyết định không có bản ghi từ web được suy ra từ trạng thái hiện tại của sự kiện (không có người duyệt, không có thời điểm chính xác)."""
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


# Bảng điều khiển
@router.get("/dashboard/kpis")
async def dashboard_kpis(http: Request):
    """Trả các chỉ số KPI của bảng điều khiển, lưu đệm theo cấu hình (gấp `KPI_CACHE_FACTOR` lần thời gian đệm chung)."""
    authorization, actor = upstream.token_of(http), _actor(http)
    health = await _health()
    return await asyncio.to_thread(
        upstream.cached, "kpis", settings.admin_cache_seconds * KPI_CACHE_FACTOR,
        lambda: kpi_module.build_kpis(authorization, actor, health))


@router.get("/attention")
async def attention(http: Request):
    """Trả danh sách các mục quản trị viên cần chú ý, lưu đệm theo cấu hình."""
    authorization, actor = upstream.token_of(http), _actor(http)
    health = await _health()
    return await asyncio.to_thread(
        upstream.cached, "attention", settings.admin_cache_seconds,
        lambda: kpi_module.build_attention(authorization, actor, health))


class SummaryRequest(BaseModel):
    """Yêu cầu tóm tắt bảng điều khiển: ngôn ngữ và cờ bỏ qua bản đã lưu đệm."""
    lang: str = "vi"
    refresh: bool = False


@router.post("/dashboard/summary")
async def dashboard_summary(body: SummaryRequest, http: Request):
    """Nơi duy nhất dùng mô hình trên bảng điều khiển, và chỉ khi quản trị viên yêu cầu. Lưu đệm theo ngày và ngôn ngữ."""
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


# Báo cáo đã ghim
class PinRequest(BaseModel):
    """Dữ liệu để ghim một báo cáo: tiêu đề, câu hỏi, câu SQL, loại biểu đồ và ảnh chụp kết quả (tùy chọn)."""
    title: str = Field(..., max_length=200)
    question: str = Field("", max_length=1000)
    sql: str = Field(..., max_length=MAX_PIN_SQL)
    chart_type: str = Field("table", max_length=30)
    snapshot: Optional[list] = None


def _engine_and_guard(sql: str):
    """Kiểm tra câu SQL bằng bộ bảo vệ rồi trả về cặp (engine phân tích, SQL đã được bảo vệ); SQL không hợp lệ thì ném `AI_INPUT_INVALID`."""
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
    """Liệt kê các báo cáo đã ghim của quản trị viên hiện tại cùng số lượng ghim tối đa."""
    return {"pins": store.list_pins(_admin_sub(http)), "max": settings.ai_pins_max_per_admin}


@router.post("/pins")
def create_pin(body: PinRequest, http: Request):
    """Ghim một báo cáo mới; trả lỗi 409 nếu quản trị viên đã đạt giới hạn số ghim. Ảnh chụp bị cắt còn `MAX_SNAPSHOT_ROWS` dòng."""
    admin = _admin_sub(http)
    _engine_and_guard(body.sql)  # SQL được kiểm tra lại ở đây và mỗi lần chạy; không bao giờ tin client
    if store.count_pins(admin) >= settings.ai_pins_max_per_admin:
        raise HTTPException(status_code=409, detail={"code": "PIN_LIMIT", "message": f"Mỗi quản trị viên chỉ ghim tối đa {settings.ai_pins_max_per_admin} báo cáo."})
    snapshot = (body.snapshot or [])[:MAX_SNAPSHOT_ROWS]
    return {"pin_id": store.create_pin(admin, body.title, body.question, body.sql, body.chart_type, snapshot)}


@router.get("/pins/{pin_id}/data")
def pin_data(pin_id: str, http: Request):
    """Chạy lại truy vấn đã ghim (không dùng mô hình). Nếu lỗi thì trả ảnh chụp đã lưu và đánh dấu là dữ liệu cũ."""
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
    """Xóa một báo cáo đã ghim của quản trị viên hiện tại; trả 404 nếu không tìm thấy."""
    if not store.delete_pin(_admin_sub(http), pin_id):
        raise HTTPException(status_code=404, detail={"code": "PIN_NOT_FOUND", "message": "Không tìm thấy báo cáo đã ghim."})
    return {"deleted": True}


# Trợ lý
MAX_CHAT_MESSAGE_CHARS = 4000
ADMIN_CHAT_SCOPE = "admin"


def _history_owner(http: Request, response: Response) -> str:
    subject = _admin_sub(http)
    issuer = http.state.claims.get("iss") or ""
    expected = http.headers.get("X-Admin-Chat-Owner")
    if expected is not None and expected != (f"{issuer}#{subject}" if issuer else subject):
        raise AiError("AI_CHAT_OWNER_CHANGED", 409, "Tài khoản quản trị đã thay đổi.")
    response.headers["Cache-Control"] = "private, no-store"
    return hashlib.sha256(json.dumps([issuer, subject]).encode()).hexdigest()


def _conversation_summary(row: dict) -> dict:
    return {"id": row["_id"], **{field: row[field] for field in ("title", "is_pinned", "created_at", "updated_at")}}


def _conversation_missing() -> AiError:
    return AiError("AI_CHAT_NOT_FOUND", 404, "Không tìm thấy hội thoại.")


class AdminConversationCreate(BaseModel):
    id: UUID


class AdminConversationPin(BaseModel):
    is_pinned: bool


@router.get("/assistant-conversations")
def list_admin_chat_conversations(owner: str = Depends(_history_owner)):
    with organizer_chat_store("admin") as storage:
        return [_conversation_summary(row) for row in storage.list_conversations(owner, ADMIN_CHAT_SCOPE, None)]


@router.post("/assistant-conversations")
def create_admin_chat_conversation(request: AdminConversationCreate, owner: str = Depends(_history_owner)):
    conversation_id = str(request.id)
    with organizer_chat_store("admin") as storage:
        try:
            row = storage.create_conversation(owner, ADMIN_CHAT_SCOPE, None, conversation_id=conversation_id)
        except DuplicateKeyError:
            row = storage.get_conversation(owner, conversation_id)
            if row is None:
                raise _conversation_missing()
        return _conversation_summary(row)


@router.get("/assistant-conversations/{conversation_id}")
def get_admin_chat_conversation(conversation_id: UUID, owner: str = Depends(_history_owner)):
    with organizer_chat_store("admin") as storage:
        row = storage.get_conversation(owner, str(conversation_id))
        if row is None:
            raise _conversation_missing()
        messages = [{
            "id": str(message["_id"]), "role": message["role"], "content": message["content"],
            "created_at": message["created_at"], **(message.get("payload") or {}),
        } for message in storage.get_messages(row)]
        return {**_conversation_summary(row), "messages": messages}


@router.patch("/assistant-conversations/{conversation_id}")
def pin_admin_chat_conversation(conversation_id: UUID, request: AdminConversationPin, owner: str = Depends(_history_owner)):
    with organizer_chat_store("admin") as storage:
        row = storage.get_conversation(owner, str(conversation_id))
        if row is None:
            raise _conversation_missing()
        updated = storage.set_pinned(row, request.is_pinned)
        if updated is None:
            raise AiError("AI_CHAT_CHANGED", 409, "Hội thoại vừa thay đổi. Vui lòng thử lại.")
        return _conversation_summary(updated)


@router.delete("/assistant-conversations/{conversation_id}")
def delete_admin_chat_conversation(conversation_id: UUID, owner: str = Depends(_history_owner)):
    with organizer_chat_store("admin") as storage:
        if not storage.delete_conversation(owner, str(conversation_id)):
            raise _conversation_missing()
        return {"deleted": True}


@router.delete("/assistant-conversations")
def clear_admin_chat_conversations(owner: str = Depends(_history_owner)):
    with organizer_chat_store("admin") as storage:
        storage.clear_conversations(owner, ADMIN_CHAT_SCOPE, None)
        return {"deleted": True}


class AdminChatRequest(BaseModel):
    """Tin nhắn mới và lịch sử hội thoại gửi cho trợ lý quản trị."""
    message: str
    history: list[dict] = Field(default_factory=list)
    conversation_id: UUID | None = None
    request_id: UUID | None = None


class AdminChatResponse(BaseModel):
    """Câu trả lời của trợ lý, danh sách công cụ đã dùng và cờ cho biết câu trả lời có dựa trên dữ liệu thật hay không."""
    reply: str
    tools_used: list[str] = []
    grounded: bool = True


@router.post("/assistant-chat", response_model=AdminChatResponse)
def chat_admin_assistant(request: AdminChatRequest, http: Request, response: Response,
                         _limit: None = Depends(rate_limit("admin_assistant"))):
    """Trợ lý có công cụ chỉ đọc. Giữ nguyên đường dẫn và trường `reply` như trước; `tools_used` và `grounded` là trường mới."""
    message = request.message.strip()
    if not message or len(request.message) > MAX_CHAT_MESSAGE_CHARS:
        raise ai_error(AI_INPUT_INVALID)
    ctx = assistant.AssistantContext(authorization=upstream.token_of(http), actor=_actor(http), health=assistant.default_health)
    if request.conversation_id is None and request.request_id is None:
        return AdminChatResponse(**assistant.run(request.message, request.history, ctx, ai_call.ai_invoke))
    if request.conversation_id is None or request.request_id is None:
        raise ai_error(AI_INPUT_INVALID)

    owner = _history_owner(http, response)
    conversation_id, request_id = str(request.conversation_id), str(request.request_id)
    request_hash = hashlib.sha256(json.dumps([conversation_id, message]).encode()).hexdigest()
    with organizer_chat_store("admin") as storage:
        row = storage.get_conversation(owner, conversation_id)
        if row is None:
            raise _conversation_missing()
        saved = storage.find_reply(owner, ADMIN_CHAT_SCOPE, None, request_id)
        if saved is not None:
            if saved["request_hash"] != request_hash:
                raise AiError("AI_CHAT_REQUEST_CONFLICT", 409, "Mã gửi lại đã được dùng cho một tin nhắn khác.")
            return AdminChatResponse(reply=saved["content"], **saved["payload"])

        history = [{"role": item["role"], "content": item["content"]} for item in storage.get_messages(row, limit=10)]
        reply = AdminChatResponse(**assistant.run(message, history, ctx, ai_call.ai_invoke))
        try:
            storage.append_turn(owner=owner, scope=ADMIN_CHAT_SCOPE, event_id=None, conversation=row,
                                question=message, response={**reply.model_dump(), "status": "ok"}, events=[],
                                request_id=request_id, request_hash=request_hash)
        except (ConversationChanged, DuplicateKeyError):
            saved = storage.find_reply(owner, ADMIN_CHAT_SCOPE, None, request_id)
            if saved is not None and saved["request_hash"] == request_hash:
                return AdminChatResponse(reply=saved["content"], **saved["payload"])
            if storage.get_conversation(owner, conversation_id) is None:
                raise _conversation_missing()
            raise AiError("AI_CHAT_CHANGED", 409, "Hội thoại vừa thay đổi. Vui lòng gửi lại.")
        return reply
