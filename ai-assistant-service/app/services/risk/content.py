"""What an assessment looks at: the texts that are scanned and the fields that make up the content hash."""
import html
import re

from app.services.risk.engine import content_hash

_TAG = re.compile(r"<[^>]+>")
_SPACE = re.compile(r"[ \t\r\f\v]+")


_BLOCK_END = re.compile(r"</\s*(?:p|div|li|ul|ol|h[1-6]|tr|table|section|blockquote)\s*>|<\s*br\s*/?\s*>", re.IGNORECASE)
_LIST_ITEM = re.compile(r"<\s*li(?:\s[^>]*)?>", re.IGNORECASE)


def plain_text(value) -> str:
    """Text of a description. Paragraph, line and list tags of an HTML description become line breaks (and list items a bullet),
    so the rules can tell a list from the sentence that introduces it."""
    raw = str(value or "")
    raw = _LIST_ITEM.sub("\n● ", _BLOCK_END.sub("\n", raw))
    text = html.unescape(_TAG.sub(" ", raw))
    lines = [_SPACE.sub(" ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


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
