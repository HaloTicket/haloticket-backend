"""SQL fragments and small helpers shared by the customer chatbot paths (chat-search, smart-booking-chat, tools).

All of it runs on the read-only chatbot engines (app.db.engines.get_chatbot_engine); nothing here touches the shared
account. Columns used on event_db: events(id,title,description,banner_url,thumbnail_url,location_name,city,ward,address,
dates_summary,min_price,event_status,end_time,is_private,created_at).
"""
from types import SimpleNamespace

# One definition of "still valid" for every customer path (alias `e` = events). Applied inside SQL, before any top-k cut.
VALID_EVENT_FILTER = (
    "e.event_status IN ('LIVE', 'APPROVED') "
    "AND e.is_private IS NOT TRUE "
    "AND (e.end_time IS NULL OR e.end_time > now())"
)

# Cosine similarity below this is not a match (chat-search); applied in SQL.
SEARCH_MIN_SCORE = 0.35

EVENT_SELECT = """
    SELECT e.id, e.title, e.description, e.banner_url, e.thumbnail_url,
           e.location_name, e.city, e.ward, e.address, e.dates_summary,
           COALESCE(e.min_price, 0) AS min_price
    FROM events e
"""


def escape_like(term: str) -> str:
    """Make %, _ and \\ in user text literal inside an ILIKE pattern (use with ESCAPE '\\')."""
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def event_dict(row) -> dict:
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
        "minPrice": float(row.min_price) if row.min_price else 0,
    }


def vector_hit(event_id, content, score) -> SimpleNamespace:
    """Same shape the callers always used: r.EventVector.event_id / .content and r.distance (cosine distance)."""
    return SimpleNamespace(EventVector=SimpleNamespace(event_id=event_id, content=content), distance=1.0 - float(score))
