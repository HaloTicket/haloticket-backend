"""Các route AI công khai cho khách hàng: tìm kiếm sự kiện bằng ngôn ngữ tự nhiên và chatbot đặt vé thông minh."""
import hashlib
import json
import logging
import re
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.encoders import jsonable_encoder
from pydantic import AwareDatetime, BaseModel, Field, field_validator
from pymongo.errors import DuplicateKeyError
from sqlalchemy import bindparam, text
from sqlalchemy.exc import SQLAlchemyError

from app.core.errors import AiError, ai_error, AI_UNAVAILABLE
from app.core.ratelimit import actor_of, rate_limit
from app.core.security import get_optional_claims
from app.db.engines import get_chatbot_engine
from app.db.session import get_db  # noqa: F401  (kept importable; the customer routes no longer use the shared session)
from app.services.ai import search_cache
from app.services.ai.booking_agent import actor_var, booking_agent
from app.services.ai.customer_chat_store import CustomerChatChanged, customer_chat_store
from app.services.ai.customer_sql import EVENT_SELECT, VALID_EVENT_FILTER, escape_like, event_dict
from app.services.ai.rag_engine import EmbeddingUnavailable, rag_engine

log = logging.getLogger("ai.customer")

router = APIRouter()

# Giới hạn nhẹ chống lạm dụng endpoint công khai này (không cần đăng nhập). Câu hỏi quá dài hoặc top_k quá lớn sẽ trả 422.
MAX_TEXT_CHARS = 500
MAX_TOP_K = 10
# Widget web gửi cả hội thoại mỗi lần, nên lịch sử dài được cắt còn các mục mới nhất chứ không bị từ chối.
MAX_HISTORY_ITEMS = 10
MAX_HISTORY_ITEM_CHARS = 2000
MAX_EVENT_ID_CHARS = 64
MAX_IMPORTED_MESSAGES = 500
KEYWORD_FALLBACK_BELOW = 3  # tìm theo từ khóa bổ sung khi tìm vector ra ít sự kiện hơn ngưỡng này


class SearchRequest(BaseModel):
    """Yêu cầu tìm kiếm: câu truy vấn tự nhiên và số kết quả vector tối đa `top_k`."""
    query: str = Field(max_length=MAX_TEXT_CHARS)
    top_k: int = Field(default=6, ge=1, le=MAX_TOP_K)


def _vector_step(engine, query: str, top_k: int):
    """Trả `[(event_id, score)]` từ tìm kiếm vector, hoặc `None` khi không chạy được (lỗi embedding hoặc truy vấn vector)."""
    try:
        with engine.connect() as conn:
            hits = rag_engine.search_events(conn, query, top_k=top_k)
        return [(str(h.EventVector.event_id), round(float(1 - h.distance), 3)) for h in hits]
    except EmbeddingUnavailable:
        log.warning("chat-search: embedding unavailable, degrading to keyword search")
    except SQLAlchemyError as failure:
        log.warning("chat-search: vector query failed (%s), degrading to keyword search", type(failure).__name__)
    return None


def _details(conn, event_ids):
    """Đọc thông tin chi tiết các sự kiện theo mã, chỉ lấy sự kiện còn hiệu lực; trả về dict `id -> sự kiện`."""
    q = text(EVENT_SELECT + f" WHERE e.id IN :eids AND {VALID_EVENT_FILTER}").bindparams(bindparam("eids", expanding=True))
    return {row.id: event_dict(row) for row in conn.execute(q, {"eids": list(event_ids)}).fetchall()}


def _keyword_rows(conn, query: str, limit: int):
    """Tìm sự kiện còn hiệu lực theo từ khóa (ILIKE trên tiêu đề, mô tả, địa điểm, thành phố), mới tạo nhất trước."""
    q = text(EVENT_SELECT + f"""
        WHERE (e.title ILIKE :kw ESCAPE '\\' OR e.description ILIKE :kw ESCAPE '\\'
               OR e.location_name ILIKE :kw ESCAPE '\\' OR e.city ILIKE :kw ESCAPE '\\')
          AND {VALID_EVENT_FILTER}
        ORDER BY e.created_at DESC
        LIMIT :lim
    """)
    return conn.execute(q, {"kw": f"%{escape_like(query)}%", "lim": limit}).fetchall()


@router.post("/chat-search")
def semantic_search(
    request: SearchRequest,
    claims=Depends(get_optional_claims),
    _limit=Depends(rate_limit("customer_search")),
):
    """
    Tìm kiếm sự kiện bằng ngôn ngữ tự nhiên: vector (ngữ nghĩa) + từ khóa, chỉ trả sự kiện còn hiệu lực.
    """
    clean_query = request.query.strip() if request.query else ""
    if not clean_query:
        return {
            "query": request.query,
            "total": 0,
            "matches": [],
            "events": []
        }

    key = search_cache.make_key(clean_query, request.top_k)
    cached = search_cache.get(key)
    if cached is not None:
        return {**cached, "query": request.query}

    engine = get_chatbot_engine("event")  # ném AiError AI_DB_NOT_CONFIGURED khi chưa cấu hình role chỉ đọc

    vector_hits = _vector_step(engine, clean_query, request.top_k)
    degraded = vector_hits is None
    hits = vector_hits or []

    try:
        events_map = {}
        with engine.connect() as conn:
            if hits:
                events_map = _details(conn, [eid for eid, _ in hits])
            if degraded or len(events_map) < KEYWORD_FALLBACK_BELOW:
                for row in _keyword_rows(conn, clean_query, request.top_k):
                    if row.id not in events_map:
                        events_map[row.id] = event_dict(row)
            conn.rollback()
    except SQLAlchemyError as failure:
        log.warning("chat-search: database error (%s)", type(failure).__name__)
        raise ai_error(AI_UNAVAILABLE)

    ordered_events, seen = [], set()
    for eid, score in hits:  # kết quả vector xếp trước, điểm cao nhất trước, kèm độ tương đồng cosine thật
        if eid in events_map and eid not in seen:
            seen.add(eid)
            ordered_events.append({**events_map[eid], "relevanceScore": score})
    for eid, ev in events_map.items():  # kết quả chỉ từ khóa không có độ tương đồng: để null, không bịa số
        if eid not in seen:
            seen.add(eid)
            ordered_events.append({**ev, "relevanceScore": None})

    response = {
        "query": request.query,
        "total": len(ordered_events),
        "matches": [{"event_id": eid, "score": score} for eid, score in hits],
        "events": ordered_events,
        "search_mode": "keyword" if degraded else "vector",
        "degraded": degraded,
    }
    if not degraded:  # kết quả suy giảm không đáng lưu đệm nhiều phút
        search_cache.put(key, response)
    return response


class ChatRequest(BaseModel):
    """Tin nhắn mới và lịch sử hội thoại gửi cho chatbot đặt vé."""
    message: str = Field(max_length=MAX_TEXT_CHARS)
    history: list = Field(default_factory=list)
    # sự kiện khách đang đặt vé do giao diện nhớ giúp; chỉ được dùng nếu là sự kiện còn hiệu lực (kiểm chứng trong cơ sở dữ liệu)
    selected_event_id: Optional[str] = Field(default=None, max_length=MAX_EVENT_ID_CHARS)
    request_id: UUID | None = None
    display_message: str | None = Field(default=None, min_length=1, max_length=MAX_TEXT_CHARS)
    generation: int = Field(default=0, ge=0)

    @field_validator("history")
    @classmethod
    def keep_latest_entries(cls, history: list) -> list:
        """Giữ lại `MAX_HISTORY_ITEMS` mục mới nhất của lịch sử và cắt nội dung mỗi mục còn tối đa `MAX_HISTORY_ITEM_CHARS` ký tự."""
        trimmed = []
        for item in history[-MAX_HISTORY_ITEMS:]:
            if isinstance(item, dict) and isinstance(item.get("content"), str) and len(item["content"]) > MAX_HISTORY_ITEM_CHARS:
                item = {**item, "content": item["content"][:MAX_HISTORY_ITEM_CHARS]}
            trimmed.append(item)
        return trimmed


class LegacyMessage(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(max_length=MAX_HISTORY_ITEM_CHARS)
    created_at: AwareDatetime
    prompt: str | None = Field(default=None, max_length=MAX_TEXT_CHARS)


class LegacyHistory(BaseModel):
    messages: list[LegacyMessage] = Field(max_length=MAX_IMPORTED_MESSAGES)


def _chat_owner(request: Request, claims: dict | None) -> str:
    guest = request.headers.get("X-Customer-Chat-Guest")
    expected_subject = request.headers.get("X-Customer-Chat-Subject")
    subject = (claims or {}).get("sub")
    if isinstance(subject, str) and subject:
        if guest is not None or expected_subject not in (None, subject):
            raise AiError("AI_CHAT_OWNER_CHANGED", 401, "Tài khoản đã thay đổi trong lúc mở cuộc trò chuyện.")
        identity = json.dumps(["account", claims.get("iss"), subject])
    elif not request.headers.get("Authorization") and expected_subject is None and guest and re.fullmatch(r"[0-9a-f]{64}", guest):
        identity = "guest:" + guest
    else:
        raise AiError("UNAUTHENTICATED", 401, "Phiên trò chuyện không hợp lệ.")
    return hashlib.sha256(identity.encode()).hexdigest()


def _history_owner(request: Request, response: Response, claims=Depends(get_optional_claims)) -> str:
    response.headers["Cache-Control"] = "private, no-store"
    return _chat_owner(request, claims)


@router.get("/chat-history")
def chat_history(owner: str = Depends(_history_owner), before: str | None = Query(default=None, pattern=r"^[0-9a-f]{24}$")):
    with customer_chat_store() as store:
        return store.detail(owner, before)


@router.put("/chat-history")
def import_chat_history(history: LegacyHistory, owner: str = Depends(_history_owner),
                        _limit=Depends(rate_limit("customer_history"))):
    with customer_chat_store() as store:
        # Only import text: booking actions must come from the current server's tools.
        messages = [message.model_dump(exclude_none=True) for message in history.messages if message.content.strip()]
        for message in messages:
            if message["role"] == "assistant":
                message.pop("prompt", None)
        legacy_hash = hashlib.sha256(json.dumps(jsonable_encoder(messages), sort_keys=True).encode()).hexdigest()
        imported = store.import_history(owner, messages, legacy_hash)
        return {**store.detail(owner), "imported": imported}


@router.delete("/chat-history")
def reset_chat_history(owner: str = Depends(_history_owner), _limit=Depends(rate_limit("customer_history"))):
    with customer_chat_store() as store:
        generation = store.reset(owner)
        return {"generation": generation, "older_cursor": None, "messages": []}


def _saved_reply(store, owner: str, request_id: str, request_hash: str) -> dict | None:
    reply = store.find_reply(owner, request_id)
    if not reply:
        return None
    if reply["request_hash"] != request_hash:
        raise AiError("AI_CHAT_REQUEST_CONFLICT", 409, "Mã gửi lại đã được dùng cho một tin nhắn khác.")
    return {"reply": reply["content"], "system_actions": reply["system_actions"], "conversation": store.detail(owner)}


def _persistent_chat(owner: str, request: ChatRequest, extra: dict) -> dict:
    request_id = str(request.request_id)
    display_message = request.display_message or request.message
    request_hash = hashlib.sha256(json.dumps([request.message, display_message, request.selected_event_id]).encode()).hexdigest()
    with customer_chat_store() as store:
        revision, generation = store.version(owner)
        if request.generation != generation:
            raise AiError("AI_CHAT_RESET", 409, "Cuộc trò chuyện đã được xóa.")
        saved = _saved_reply(store, owner, request_id, request_hash)
        if saved is not None:
            return saved
        rows = store.latest_messages(owner, MAX_HISTORY_ITEMS, revision=revision) if revision else []
        history = [{"role": row["role"], "content": row.get("prompt", row["content"])[:MAX_HISTORY_ITEM_CHARS]} for row in rows]
        response = jsonable_encoder(booking_agent.chat(request.message, history, None, **extra))
        try:
            store.append_turn(owner=owner, revision=revision, prompt=request.message, display_message=display_message,
                              reply=response["reply"], actions=response["system_actions"], request_id=request_id,
                              request_hash=request_hash)
        except (CustomerChatChanged, DuplicateKeyError):
            if store.version(owner)[1] != generation:
                raise AiError("AI_CHAT_RESET", 409, "Cuộc trò chuyện đã được xóa.")
            saved = _saved_reply(store, owner, request_id, request_hash)
            if saved is not None:
                return saved
            raise AiError("AI_CHAT_CHANGED", 409, "Cuộc trò chuyện vừa thay đổi. Vui lòng gửi lại.")
        return {**response, "conversation": store.detail(owner)}


@router.post("/smart-booking-chat")
def smart_booking(
    request: ChatRequest,
    http_request: Request,
    response: Response,
    claims=Depends(get_optional_claims),
    _limit=Depends(rate_limit("customer_chat")),
):
    """Chatbot đặt vé thông minh; đặt định danh người gọi vào context để giới hạn và ghi nhận, lỗi bất ngờ được che bằng `AI_UNAVAILABLE`."""
    token = actor_var.set(actor_of(http_request, claims))
    try:
        extra = {"selected_event_id": request.selected_event_id} if request.selected_event_id else {}
        if request.request_id is not None:
            owner = _chat_owner(http_request, claims)
            response.headers["Cache-Control"] = "private, no-store"
            return _persistent_chat(owner, request, extra)
        return booking_agent.chat(request.message, request.history, None, **extra)
    except AiError:
        raise  # 429 / 503 / 502 kèm mã lỗi riêng
    except Exception as failure:
        # chi tiết chỉ ghi log theo tên kiểu lỗi; client nhận thông điệp cố định theo hợp đồng lỗi
        log.error("smart-booking-chat failed: %s", type(failure).__name__, exc_info=True)
        raise ai_error(AI_UNAVAILABLE)
    finally:
        actor_var.reset(token)
