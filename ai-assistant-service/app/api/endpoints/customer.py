"""Các route AI công khai cho khách hàng: tìm kiếm sự kiện bằng ngôn ngữ tự nhiên và chatbot đặt vé thông minh."""
import logging

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import bindparam, text
from sqlalchemy.exc import SQLAlchemyError

from app.core.errors import AiError, ai_error, AI_UNAVAILABLE
from app.core.ratelimit import actor_of, rate_limit
from app.core.security import get_optional_claims
from app.db.engines import get_chatbot_engine
from app.db.session import get_db  # noqa: F401  (kept importable; the customer routes no longer use the shared session)
from app.services.ai import search_cache
from app.services.ai.booking_agent import actor_var, booking_agent
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


@router.post("/smart-booking-chat")
def smart_booking(
    request: ChatRequest,
    http_request: Request,
    claims=Depends(get_optional_claims),
    _limit=Depends(rate_limit("customer_chat")),
):
    """Chatbot đặt vé thông minh; đặt định danh người gọi vào context để giới hạn và ghi nhận, lỗi bất ngờ được che bằng `AI_UNAVAILABLE`."""
    token = actor_var.set(actor_of(http_request, claims))
    try:
        return booking_agent.chat(request.message, request.history, None)
    except AiError:
        raise  # 429 / 503 / 502 kèm mã lỗi riêng
    except Exception as failure:
        # chi tiết chỉ ghi log theo tên kiểu lỗi; client nhận thông điệp cố định theo hợp đồng lỗi
        log.error("smart-booking-chat failed: %s", type(failure).__name__, exc_info=True)
        raise ai_error(AI_UNAVAILABLE)
    finally:
        actor_var.reset(token)
