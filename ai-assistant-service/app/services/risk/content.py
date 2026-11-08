"""What an assessment looks at: the texts that are scanned and the fields that make up the content hash."""
import html
import re

from app.services.risk.engine import content_hash

_TAG = re.compile(r"<[^>]+>")
_SPACE = re.compile(r"[ \t\r\f\v]+")


def plain_text(value) -> str:
    text = html.unescape(_TAG.sub(" ", str(value or "")))
    return _SPACE.sub(" ", text).strip()


def _tickets(event: dict) -> list:
    raw = event.get("tickets")
    out = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict):
                out.append({"name": str(item.get("name") or "")[:120], "price": item.get("price")})
    return out


def build(event: dict) -> dict:
    """{texts, hash, organizer_user_id, organizer_name, category} from an EventResponse-shaped dict."""
    organizer = event.get("organizer") if isinstance(event.get("organizer"), dict) else {}
    description = plain_text(event.get("descriptionText") or event.get("description"))
    texts = {"title": plain_text(event.get("title")), "description": description}
    parts = {
        "title": texts["title"], "description": description, "tickets": _tickets(event),
        "location": [event.get("locationType"), event.get("locationName"), event.get("address"), event.get("city")],
        "time": [event.get("startTime"), event.get("endTime")],
        "organizer": organizer.get("userId") or organizer.get("id") or event.get("organizerName"),
        "category": event.get("categoryId") or event.get("categoryName"),
    }
    return {
        "texts": texts,
        "hash": content_hash(parts),
        "organizer_user_id": organizer.get("userId"),
        "organizer_name": organizer.get("name") or event.get("organizerName") or "",
        "category": event.get("categoryName"),
    }


def from_request(event_id: str, title: str, description: str, organizer_name: str) -> dict:
    """Fallback when the event cannot be read from the event service: the texts the client sent."""
    return build({"id": event_id, "title": title, "description": description, "organizerName": organizer_name})
