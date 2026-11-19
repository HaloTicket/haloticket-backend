"""Đoạn SQL và hàm phụ dùng chung cho các luồng chatbot khách hàng (chat-search, smart-booking-chat, tools).

Tất cả chạy trên engine chatbot chỉ đọc (app.db.engines.get_chatbot_engine); không đụng tới tài khoản dùng chung.
Các cột dùng trên event_db: events(id,title,description,banner_url,thumbnail_url,location_name,city,ward,address,
dates_summary,min_price,event_status,end_time,is_private,created_at).
"""
import re
import unicodedata
from types import SimpleNamespace

# Một định nghĩa duy nhất cho "sự kiện còn hiệu lực" ở mọi luồng khách hàng (bí danh `e` = events). Áp dụng ngay trong SQL, trước khi cắt top-k.
# Cùng quy tắc với event-service (GET /api/events/search): đã duyệt, không riêng tư, và còn bán: có hạn đóng bán thì còn trước hạn đó,
# không có thì còn trước giờ kết thúc. Sự kiện hết hạn bán không được giới thiệu như còn vé.
VALID_EVENT_FILTER = (
    "e.event_status IN ('LIVE', 'APPROVED') "
    "AND e.is_private IS NOT TRUE "
    "AND (e.sale_end_time > now() OR (e.sale_end_time IS NULL AND (e.end_time IS NULL OR e.end_time > now())))"
)

# Chữ cái tiếng Việt có dấu (thường) và bản không dấu tương ứng, để so khớp không dấu bằng hàm translate() của PostgreSQL
# (không cần extension unaccent). Mỗi chữ có dấu xuất hiện ở cả dạng thường lẫn hoa; đích luôn là chữ thường không dấu.
_ACCENTED_LOWER = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"


def fold_text(value) -> str:
    """Bỏ dấu, đổi đ thành d và đưa về chữ thường ('Hà Nội' -> 'ha noi')."""
    text = unicodedata.normalize("NFD", "" if value is None else str(value))
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    return text.replace("đ", "d").replace("Đ", "d").lower()


ACCENT_FROM = "".join(c + c.upper() for c in _ACCENTED_LOWER)
ACCENT_TO = "".join(fold_text(c) * 2 for c in _ACCENTED_LOWER)


def fold_sql(column: str) -> str:
    """Biểu thức SQL bỏ dấu và hạ chữ thường của một cột, dùng với giá trị đã qua `fold_text` ở phía tham số."""
    return f"lower(translate(coalesce({column}, ''), '{ACCENT_FROM}', '{ACCENT_TO}'))"


# Cách gọi tắt quen thuộc của các thành phố (đã bỏ dấu), mở rộng thành các cụm thật sự có trong địa chỉ.
CITY_ABBREVIATIONS = {
    "hcm": ["ho chi minh", "hcm"],
    "tphcm": ["ho chi minh", "hcm"],
    "sg": ["sai gon", "ho chi minh"],
    "saigon": ["sai gon", "ho chi minh"],
    "hn": ["ha noi"],
    "hanoi": ["ha noi"],
    "dn": ["da nang"],
    "danang": ["da nang"],
    "dalat": ["da lat"],
}


def expand_token(token: str) -> list:
    """Các cụm (đã bỏ dấu) mà một từ khóa có thể khớp: chính nó, hoặc cách gọi đầy đủ nếu là từ viết tắt thành phố."""
    folded = fold_text(token).strip()
    return CITY_ABBREVIATIONS.get(folded, [folded]) if folded else []

# Độ tương đồng cosine thấp hơn ngưỡng này thì không tính là khớp (chat-search); áp dụng trong SQL.
SEARCH_MIN_SCORE = 0.35

EVENT_SELECT = """
    SELECT e.id, e.title, e.description, e.banner_url, e.thumbnail_url,
           e.location_name, e.city, e.ward, e.address, e.dates_summary,
           COALESCE(e.min_price, 0) AS min_price
    FROM events e
"""


def escape_like(term: str) -> str:
    """Thoát %, _ và \\ trong chữ người dùng để chúng thành ký tự thường trong mẫu ILIKE (dùng kèm ESCAPE '\\')."""
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def event_dict(row) -> dict:
    """Chuyển một dòng kết quả SQL của bảng events thành dict camelCase cho giao diện; trường rỗng thành chuỗi rỗng, ảnh bìa và ảnh nhỏ dự phòng cho nhau."""
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
    """Dựng đối tượng có cùng hình dạng mà các nơi gọi vẫn dùng: r.EventVector.event_id / .content và r.distance (khoảng cách cosine)."""
    return SimpleNamespace(EventVector=SimpleNamespace(event_id=event_id, content=content), distance=1.0 - float(score))
