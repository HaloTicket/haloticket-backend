from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import text
from app.db.session import get_db
from app.services.ai.rag_engine import rag_engine
from app.services.ai.booking_agent import booking_agent

router = APIRouter()

# light limits against abuse of this public endpoint (no login). Too long a question or too large a top_k answers 422.
MAX_TEXT_CHARS = 500
MAX_TOP_K = 10
# the web widget sends the whole conversation every time, so a long history is cut to the latest entries, not refused.
MAX_HISTORY_ITEMS = 10
MAX_HISTORY_ITEM_CHARS = 2000

EVENT_SELECT = '''
    SELECT e.id, e.title, e.description, e.banner_url, e.thumbnail_url,
           e.location_name, e.city, e.ward, e.address, e.dates_summary,
           COALESCE((SELECT MIN(price) FROM ticket_types WHERE event_id = e.id AND ticket_type_status = 'ACTIVE'), e.min_price, 0) as min_price
    FROM events e
'''


def _event_dict(row) -> dict:
    return {
        "id": row.id,
        "title": row.title,
        "description": row.description or "",
        "bannerUrl": row.banner_url or row.thumbnail_url or "",
        "thumbnailUrl": row.thumbnail_url or row.banner_url or "",
        "locationName": row.location_name or "",
        "city": row.city or "",
        "ward": row.ward or "",
        "address": row.address or "",
        "datesSummary": row.dates_summary or "",
        "minPrice": float(row.min_price) if row.min_price else 0
    }


class SearchRequest(BaseModel):
    query: str = Field(max_length=MAX_TEXT_CHARS)
    top_k: int = Field(default=6, ge=1, le=MAX_TOP_K)

@router.post("/chat-search")
def semantic_search(request: SearchRequest, db = Depends(get_db)):
    """
    Tìm kiếm sự kiện bằng ngôn ngữ tự nhiên (Semantic AI Search)
    kết hợp tìm kiếm Vector theo ngữ nghĩa và thông tin chi tiết sự kiện từ cơ sở dữ liệu.
    """
    clean_query = request.query.strip() if request.query else ""
    if not clean_query:
        return {
            "query": request.query,
            "total": 0,
            "matches": [],
            "events": []
        }

    # vector semantic search via RAG engine
    results = rag_engine.search_events(db, clean_query, top_k=request.top_k)

    # extract matched event IDs with decent semantic similarity
    event_ids = [r.EventVector.event_id for r in results if (1 - r.distance) >= 0.35]

    # retrieve rich event details from events & ticket_types tables
    events_map = {}
    if event_ids:
        q = text(EVENT_SELECT + '''
            WHERE e.id IN :eids
              AND e.event_status = 'LIVE'
              AND (e.end_time IS NULL OR e.end_time >= NOW())
        ''').bindparams(eids=tuple(event_ids))

        # session.execute, not the session connection used as a context manager: leaving that block closes the connection, and the
        # keyword query below then failed with ResourceClosedError whenever the vector search had found events.
        for row in db.execute(q).fetchall():
            events_map[row.id] = _event_dict(row)

    # fallback keyword search if semantic search yields fewer than 3 events
    if len(events_map) < 3:
        keyword_term = f"%{clean_query}%"
        fallback_query = text(EVENT_SELECT + '''
            WHERE (e.title ILIKE :kw OR e.description ILIKE :kw OR e.location_name ILIKE :kw OR e.city ILIKE :kw)
              AND e.event_status = 'LIVE'
              AND (e.end_time IS NULL OR e.end_time >= NOW())
            LIMIT 5
        ''')
        for row in db.execute(fallback_query, {"kw": keyword_term}).fetchall():
            if row.id not in events_map:
                events_map[row.id] = {**_event_dict(row), "relevanceScore": 0.85}

    # order events by similarity score
    ordered_events = []
    seen_ids = set()
    for r in results:
        eid = r.EventVector.event_id
        if eid in events_map and eid not in seen_ids:
            seen_ids.add(eid)
            ev = dict(events_map[eid])
            ev["relevanceScore"] = round(float(1 - r.distance), 3)
            ordered_events.append(ev)

    # append any keyword-matched events not in vector results
    for eid, ev_data in events_map.items():
        if eid not in seen_ids:
            seen_ids.add(eid)
            ordered_events.append(ev_data)

    return {
        "query": request.query,
        "total": len(ordered_events),
        "matches": [
            {
                "event_id": r.EventVector.event_id,
                "content": r.EventVector.content,
                "score": round(float(1 - r.distance), 3)
            }
            for r in results
        ],
        "events": ordered_events
    }

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
def smart_booking(request: ChatRequest, db = Depends(get_db)):
    try:
        return booking_agent.chat(request.message, request.history, db)
    except Exception as e:
        import traceback
        traceback.print_exc()
        print(f"[SMART BOOKING ERROR]: {e}")
        return {
            "reply": "Xin lỗi bạn, trợ lý AI hiện đang gặp sự cố kết nối hoặc quá tải. Bạn vui lòng thử lại sau giây lát nhé!",
            "system_actions": []
        }
