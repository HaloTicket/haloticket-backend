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

# light limits against abuse of this public endpoint (no login). Too long a question or too large a top_k answers 422.
MAX_TEXT_CHARS = 500
MAX_TOP_K = 10
# the web widget sends the whole conversation every time, so a long history is cut to the latest entries, not refused.
MAX_HISTORY_ITEMS = 10
MAX_HISTORY_ITEM_CHARS = 2000
KEYWORD_FALLBACK_BELOW = 3  # keyword search tops up when the vector search found fewer events than this


class SearchRequest(BaseModel):
    query: str = Field(max_length=MAX_TEXT_CHARS)
    top_k: int = Field(default=6, ge=1, le=MAX_TOP_K)


def _vector_step(engine, query: str, top_k: int):
    """[(event_id, score)] from the vector search, or None when it cannot run (embedding or vector query failed)."""
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
    q = text(EVENT_SELECT + f" WHERE e.id IN :eids AND {VALID_EVENT_FILTER}").bindparams(bindparam("eids", expanding=True))
    return {row.id: event_dict(row) for row in conn.execute(q, {"eids": list(event_ids)}).fetchall()}


def _keyword_rows(conn, query: str, limit: int):
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

    engine = get_chatbot_engine("event")  # AiError AI_DB_NOT_CONFIGURED when the read-only role is not set up

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
    for eid, score in hits:  # vector hits first, best score first, with the real cosine similarity
        if eid in events_map and eid not in seen:
            seen.add(eid)
            ordered_events.append({**events_map[eid], "relevanceScore": score})
    for eid, ev in events_map.items():  # keyword-only hits have no similarity: null, not an invented number
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
    if not degraded:  # a degraded answer is not worth keeping for minutes
        search_cache.put(key, response)
    return response


class ChatRequest(BaseModel):
    message: str = Field(max_length=MAX_TEXT_CHARS)
    history: list = Field(default_factory=list)

    @field_validator("history")
    @classmethod
    def keep_latest_entries(cls, history: list) -> list:
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
    token = actor_var.set(actor_of(http_request, claims))
    try:
        return booking_agent.chat(request.message, request.history, None)
    except AiError:
        raise  # 429 / 503 / 502 with their own code
    except Exception as failure:
        # the details go to the log by type only; the client gets the fixed message of the error contract
        log.error("smart-booking-chat failed: %s", type(failure).__name__, exc_info=True)
        raise ai_error(AI_UNAVAILABLE)
    finally:
        actor_var.reset(token)
