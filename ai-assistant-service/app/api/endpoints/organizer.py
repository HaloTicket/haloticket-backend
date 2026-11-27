"""Các route AI cho ban tổ chức và quản trị: sinh nội dung sự kiện, phân tích dữ liệu, chiến lược voucher và chatbot agent."""
import logging
import re
from datetime import datetime
from hashlib import sha256
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel, Field, StringConstraints, model_validator
from pymongo.errors import DuplicateKeyError
from app.api.endpoints import checkin_assistant
from app.core.config import settings
from app.services.ai.sql_engine import sql_engine
from app.services.ai.organizer_agent import organizer_agent
from app.services.ai.organizer_chat_store import ConversationChanged, OrganizerChatStore, organizer_chat_store
from app.services.ai import analysis_service, event_content
from app.core.errors import ai_error, AiError, AI_INPUT_INVALID, AI_UNAVAILABLE, AI_GENERATION_FAILED
from app.core.ratelimit import rate_limit, actor_of
from app.core.security import require_admin, require_login
from app.services.ai import scope as permissions
from app.services.ai.scope import AnalysisScope, analysis_scope

log = logging.getLogger("ai.organizer")
router = APIRouter()
router.include_router(checkin_assistant.router)

MAX_QUESTION_CHARS = 1000
MAX_EVENT_ID_CHARS = 64
_EVENT_ID = re.compile(r"^[A-Za-z0-9_-]+$")


def _actor(request: Request, claims) -> str:
    """Định danh người gọi dùng cho giới hạn tần suất và nhật ký."""
    return actor_of(request, claims)


def _engine_for(profile: str):
    """Engine của lớp có đo lường theo `profile`, trừ khi thuộc tính module `sql_engine` đã bị thay thế (test vá thuộc tính này)."""
    if sql_engine is not analysis_service.LEGACY_ENGINE:
        return sql_engine
    return analysis_service.engine_for(profile)


class GenerateContentRequest(BaseModel):
    """Tiêu đề và các ý chính để sinh mô tả sự kiện."""
    title: str
    bullet_points: str

class GenerateContentResponse(BaseModel):
    """Mô tả và điều khoản sự kiện do AI sinh ra."""
    description: str
    terms_and_conditions: str

# Phân tích dữ liệu và chiến lược voucher đọc dữ liệu sự kiện thông qua mô hình. Admin thấy mọi sự kiện; organizer chỉ thấy
# các sự kiện mình quản lý. Sinh nội dung chỉ cần đăng nhập.
# Thứ tự trên ba route này: xác thực / phạm vi, rồi giới hạn tần suất, rồi kiểm tra nội dung yêu cầu.
@router.post("/generate-event-content", response_model=GenerateContentResponse, dependencies=[Depends(require_login)])
def generate_event_content(request: GenerateContentRequest, http: Request, _limit: None = Depends(rate_limit("event_content"))):
    """
    Sinh mô tả sự kiện và điều khoản từ các ý chính (bullet points).
    Dành cho Organizer; chỉ cần đăng nhập.
    """
    result = event_content.generate_event_content(request.title, request.bullet_points,
                                                  actor=_actor(http, getattr(http.state, "claims", None)))
    return GenerateContentResponse(
        description=result.get("description", ""),
        terms_and_conditions=result.get("terms_and_conditions", "")
    )


class AnalyzeRequest(BaseModel):
    """Câu hỏi phân tích dữ liệu bằng ngôn ngữ tự nhiên."""
    question: str

@router.post("/data-analysis")
def analyze_data(request: AnalyzeRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                 _limit: None = Depends(rate_limit("data_analysis"))):
    """Trả lời câu hỏi phân tích dữ liệu trong phạm vi quyền của người gọi; câu hỏi rỗng hoặc quá dài bị từ chối."""
    if not request.question.strip() or len(request.question) > MAX_QUESTION_CHARS:
        raise ai_error(AI_INPUT_INVALID)
    try:
        return analysis_service.analyze(request.question, scope, "analysis", _actor(http, getattr(http.state, "claims", None)),
                                        engine=_engine_for("analysis"))
    except (AiError, HTTPException):
        raise  # ví dụ 503 AI_DB_NOT_CONFIGURED, 422 AI_QUESTION_REFUSED
    except Exception as failure:
        log.error("Data analysis route failed: %s", type(failure).__name__)
        raise ai_error(AI_UNAVAILABLE)

class VoucherStrategyRequest(BaseModel):
    """Mã sự kiện cần đề xuất chiến lược voucher."""
    event_id: str

@router.post("/voucher-strategy")
def voucher_strategy(request: VoucherStrategyRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                     _limit: None = Depends(rate_limit("voucher_strategy"))):
    """Đề xuất chiến lược phát hành voucher cho một sự kiện dựa trên tốc độ bán vé 7 ngày qua; yêu cầu sự kiện nằm trong phạm vi của người gọi."""
    if not scope.allows_event(request.event_id):
        raise HTTPException(status_code=403, detail={"code": "EVENT_NOT_IN_SCOPE", "message": "Bạn không có quyền phân tích sự kiện này."})
    # mã sự kiện được đưa vào nội dung câu hỏi nên chỉ chấp nhận định danh thuần
    if len(request.event_id) > MAX_EVENT_ID_CHARS or not _EVENT_ID.match(request.event_id):
        raise ai_error(AI_INPUT_INVALID)
    try:
        # hỏi SQL engine về tốc độ bán vé của sự kiện
        result = analysis_service.analyze(
            f"Phân tích tốc độ bán vé (sale velocity) của event_id {request.event_id} trong 7 ngày qua "
            "và đề xuất chiến lược phát hành voucher (tên voucher, mã voucher, phần trăm giảm giá, số lượng) "
            "để kích cầu nếu cần thiết.",
            scope, "voucher_strategy", _actor(http, getattr(http.state, "claims", None)), engine=_engine_for("voucher_strategy"),
        )
        return {"strategy": result.get("reply", "Hệ thống đang thu thập thêm dữ liệu để đưa ra đề xuất voucher phù hợp.")}
    except (AiError, HTTPException):
        raise  # ví dụ 503 AI_DB_NOT_CONFIGURED
    except Exception as failure:
        log.error("Voucher strategy route failed: %s", type(failure).__name__)
        raise ai_error(AI_GENERATION_FAILED)


ChatText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=MAX_QUESTION_CHARS)]
ChatScope = Literal["managed_events", "event"]
ChatEventId = Annotated[str, StringConstraints(max_length=MAX_EVENT_ID_CHARS, pattern=_EVENT_ID.pattern)]


def _validate_conversation_scope(scope: ChatScope, event_id: str | None):
    if (scope == "event") != (event_id is not None):
        raise ValueError("event_id is required with event scope and is not allowed with managed_events")


class OrganizerConversationScope(BaseModel):
    scope: ChatScope
    event_id: ChatEventId | None = None

    @model_validator(mode="after")
    def check_event_scope(self):
        _validate_conversation_scope(self.scope, self.event_id)
        return self


class OrganizerChatHistoryItem(BaseModel):
    role: Literal["user", "assistant"]
    content: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]


class OrganizerChatRequest(BaseModel):
    scope: ChatScope | None = None
    event_id: ChatEventId | None = None
    message: ChatText
    history: list[OrganizerChatHistoryItem] = Field(default_factory=list, max_length=8)
    locale: Literal["vi", "en"] = "vi"
    conversation_id: UUID | None = None
    request_id: UUID | None = None
    persist: bool = False

    @model_validator(mode="after")
    def check_event_scope(self):
        if self.scope is not None:
            _validate_conversation_scope(self.scope, self.event_id)
        elif self.persist or self.conversation_id or self.request_id:
            raise ValueError("scope is required to save a conversation")
        return self


class OrganizerChatPeriod(BaseModel):
    from_: str = Field(alias="from")
    to: str


class OrganizerChatMetric(BaseModel):
    key: str
    value: int | float | None
    unit: Literal["count", "VND", "percent"]
    period: OrganizerChatPeriod | None
    completeness: Literal["complete", "partial", "missing"]


class OrganizerChatCard(BaseModel):
    kind: Literal["event", "showtime", "ticket_type"]
    event_id: str
    event_title: str
    event_status: str | None
    showtime_id: str | None
    showtime_title: str | None
    ticket_type_id: str | None
    ticket_type_title: str | None
    start_time: str | None
    end_time: str | None
    metrics: list[OrganizerChatMetric]


class OrganizerChatWarning(OrganizerChatCard):
    code: Literal["paid_order_missing_tickets", "low_stock", "slow_showtime"]


class OrganizerChatDraft(BaseModel):
    title: str
    content: str


class OrganizerChatFollowUpOption(BaseModel):
    label: str
    message: ChatText


class OrganizerChatFollowUp(BaseModel):
    question: str
    options: list[OrganizerChatFollowUpOption] = Field(default_factory=list, max_length=3)


class OrganizerChatResponse(BaseModel):
    reply: str
    status: Literal["ok", "empty"] = "ok"
    conversation_id: UUID | None = None
    pipeline_steps: list[dict] = Field(default_factory=list)
    actions: list[dict] = Field(default_factory=list)
    cards: list[OrganizerChatCard] = Field(default_factory=list)
    totals: list[OrganizerChatMetric] = Field(default_factory=list)
    warnings: list[OrganizerChatWarning] = Field(default_factory=list)
    drafts: list[OrganizerChatDraft] = Field(default_factory=list)
    follow_up: OrganizerChatFollowUp | None = None
    suggested_questions: list[str] = Field(default_factory=list, max_length=3)
    show_analytics: bool | None = None
    execution_time_ms: int | None = None


class OrganizerConversationSummary(OrganizerConversationScope):
    id: UUID = Field(validation_alias="_id")
    title: str
    is_pinned: bool
    created_at: datetime
    updated_at: datetime


class OrganizerConversationMessage(BaseModel):
    id: str
    role: Literal["user", "assistant"]
    content: str
    status: Literal["ok", "empty"]
    payload: OrganizerChatResponse | None
    created_at: datetime


class OrganizerConversationDetail(OrganizerConversationSummary):
    messages: list[OrganizerConversationMessage]


class OrganizerConversationPin(BaseModel):
    is_pinned: bool


def _conversation_owner(claims: dict) -> str:
    issuer, subject = claims.get("iss"), claims.get("sub")
    if not isinstance(issuer, str) or not issuer or not isinstance(subject, str) or not subject:
        raise HTTPException(401, detail={"code": "INVALID_TOKEN", "message": "Phiên đăng nhập không hợp lệ."},
                            headers={"WWW-Authenticate": "Bearer"})
    return f"{issuer}#{subject}"


def _no_event_access() -> HTTPException:
    return HTTPException(403, detail={"code": "EVENT_NOT_IN_SCOPE",
                                      "message": "Bạn không còn quyền truy cập dữ liệu sự kiện trong phạm vi này."})


def _conversation_not_found() -> HTTPException:
    return HTTPException(404, detail={"code": "CONVERSATION_NOT_FOUND", "message": "Không tìm thấy đoạn chat."})


def _conversation_conflict() -> HTTPException:
    return HTTPException(409, detail={"code": "CONVERSATION_CONFLICT",
                                      "message": "Đoạn chat đã thay đổi. Vui lòng thử lại."})


def _chat_events(http: Request, claims: dict, event_id: str | None) -> list[dict]:
    identity = permissions.organizer_chat_identity(http, claims)
    try:
        events = permissions.chat_events(identity, event_id)
    except Exception as failure:
        log.warning("Could not resolve organizer chat scope: %s", type(failure).__name__)
        raise HTTPException(status_code=503, detail={"code": "SCOPE_UNAVAILABLE",
                                                    "message": "Chưa xác định được phạm vi dữ liệu của bạn."}) from failure
    if event_id is not None and not events:
        raise _no_event_access()
    return events


def _event_grants(events: list[dict]) -> tuple[set[str], set[str]]:
    return ({row["event_id"] for row in events},
            {row["event_id"] for row in events if row.get("can_view_finance")})


def _can_read_history(conversation: dict, grants: tuple[set[str], set[str]]) -> bool:
    view, finance = grants
    return view.issuperset(conversation["view_event_ids"]) and finance.issuperset(conversation["finance_event_ids"])


def _owned_conversation(store: OrganizerChatStore, owner: str, conversation_id: str) -> dict:
    conversation = store.get_conversation(owner, conversation_id)
    if conversation is None:
        raise _conversation_not_found()
    return conversation


def _ensure_readable(conversation: dict, events: list[dict]):
    if not _can_read_history(conversation, _event_grants(events)):
        raise _no_event_access()


def _stored_reply(message: dict) -> dict:
    return {**message["payload"], "reply": message["content"], "status": message["status"],
            "conversation_id": message["conversation_id"]}


@router.get("/conversations", response_model=list[OrganizerConversationSummary])
def list_organizer_conversations(query: Annotated[OrganizerConversationScope, Query()], http: Request,
                                 response: Response, claims: dict = Depends(require_login)):
    owner = _conversation_owner(claims)
    with organizer_chat_store() as store:
        events = _chat_events(http, claims, query.event_id)
        grants = _event_grants(events)
        conversations = store.list_conversations(owner, query.scope, query.event_id)
    response.headers["Cache-Control"] = "no-store"
    return [item for item in conversations if _can_read_history(item, grants)]


@router.post("/conversations", status_code=201, response_model=OrganizerConversationSummary)
def create_organizer_conversation(request: OrganizerConversationScope, http: Request,
                                  claims: dict = Depends(require_login)):
    owner = _conversation_owner(claims)
    with organizer_chat_store() as store:
        _chat_events(http, claims, request.event_id)
        return store.create_conversation(owner, request.scope, request.event_id)


@router.get("/conversations/{conversation_id}", response_model=OrganizerConversationDetail)
def get_organizer_conversation(conversation_id: UUID, http: Request, response: Response,
                                claims: dict = Depends(require_login)):
    owner = _conversation_owner(claims)
    with organizer_chat_store() as store:
        conversation = _owned_conversation(store, owner, str(conversation_id))
        _ensure_readable(conversation, _chat_events(http, claims, conversation["event_id"]))
        messages = [
            {**row, "id": str(row["_id"]),
             "payload": _stored_reply(row) if row["role"] == "assistant" else None}
            for row in store.get_messages(conversation)
        ]
    response.headers["Cache-Control"] = "no-store"
    return {**conversation, "messages": messages}


@router.patch("/conversations/{conversation_id}/pin", response_model=OrganizerConversationSummary)
def pin_organizer_conversation(conversation_id: UUID, request: OrganizerConversationPin, http: Request,
                                claims: dict = Depends(require_login)):
    owner = _conversation_owner(claims)
    with organizer_chat_store() as store:
        conversation = _owned_conversation(store, owner, str(conversation_id))
        _ensure_readable(conversation, _chat_events(http, claims, conversation["event_id"]))
        result = store.set_pinned(conversation, request.is_pinned)
        if result is None:
            raise _conversation_conflict()
        return result


@router.delete("/conversations/{conversation_id}", status_code=204)
def delete_organizer_conversation(conversation_id: UUID, claims: dict = Depends(require_login)):
    with organizer_chat_store() as store:
        if not store.delete_conversation(_conversation_owner(claims), str(conversation_id)):
            raise _conversation_not_found()


@router.delete("/conversations", status_code=204)
def clear_organizer_conversations(query: Annotated[OrganizerConversationScope, Query()],
                                  claims: dict = Depends(require_login)):
    with organizer_chat_store() as store:
        store.clear_conversations(_conversation_owner(claims), query.scope, query.event_id)


def _run_organizer_chat(request: OrganizerChatRequest, history: list[dict], events: list[dict], actor: str):
    try:
        return organizer_agent.chat(
            message=request.message, history=history, events=events, event_id=request.event_id,
            locale=request.locale, actor=actor,
        )
    except (AiError, HTTPException):
        raise
    except Exception as failure:
        log.warning("Organizer chat failed: %s", type(failure).__name__)
        raise ai_error(AI_UNAVAILABLE) from failure


def _save_chat_turn(store: OrganizerChatStore, request: OrganizerChatRequest, owner: str,
                    events: list[dict], actor: str):
    conversation_id = str(request.conversation_id) if request.conversation_id else None
    request_id = str(request.request_id) if request.request_id else None
    request_hash = sha256(f"{request.locale}\0{request.message}".encode()).hexdigest()

    def replay():
        if not request_id:
            return None
        row = store.find_reply(owner, request.scope, request.event_id, request_id)
        if row is None:
            return None
        if row["request_hash"] != request_hash or conversation_id not in (None, row["conversation_id"]):
            raise _conversation_conflict()
        _ensure_readable(_owned_conversation(store, owner, row["conversation_id"]), events)
        return _stored_reply(row)

    conversation = None
    if conversation_id:
        conversation = _owned_conversation(store, owner, conversation_id)
        if (conversation["scope"], conversation["event_id"]) != (request.scope, request.event_id):
            raise _conversation_not_found()
        _ensure_readable(conversation, events)

    cached = replay()
    if cached is not None:
        return cached
    history = [] if conversation is None else [
        {"role": row["role"], "content": row["content"][:4000]} for row in store.get_messages(conversation, limit=8)
    ]
    result = OrganizerChatResponse.model_validate(_run_organizer_chat(request, history, events, actor))
    payload = result.model_dump(mode="json", by_alias=True, exclude={"conversation_id"})
    try:
        saved_id = store.append_turn(owner=owner, scope=request.scope, event_id=request.event_id,
                                     conversation=conversation, question=request.message, response=payload,
                                     events=events, request_id=request_id, request_hash=request_hash)
    except (ConversationChanged, DuplicateKeyError):
        cached = replay()
        if cached is None:
            raise _conversation_conflict()
        return cached
    return {**payload, "conversation_id": saved_id}


@router.post("/agent-chat", response_model=OrganizerChatResponse)
def organizer_agent_chat(request: OrganizerChatRequest, http: Request, claims: dict = Depends(require_login),
                         _limit: None = Depends(rate_limit("organizer_chat"))):
    # Preserve scope-less admin clients and deployed clients without a configured history store.
    persistence_requested = request.persist or request.conversation_id is not None or request.request_id is not None
    # Older scoped clients always send their own history and cannot follow a server conversation ID.
    use_server_history = bool(settings.mongodb_uri) and "history" not in request.model_fields_set
    if request.scope is None:
        require_admin(claims)
    elif persistence_requested or use_server_history:
        owner = _conversation_owner(claims)
        with organizer_chat_store() as store:
            events = _chat_events(http, claims, request.event_id)
            return _save_chat_turn(store, request, owner, events, _actor(http, claims))

    events = _chat_events(http, claims, request.event_id)
    return _run_organizer_chat(request, [entry.model_dump() for entry in request.history], events, _actor(http, claims))

