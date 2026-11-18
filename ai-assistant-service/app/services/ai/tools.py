"""Các công cụ (tool) của chatbot khách hàng. Chỉ đọc: không bao giờ ghi; request_user_confirmation chỉ mô tả
thao tác mà người dùng có thể xác nhận trên web.

Engine là các role chatbot chỉ đọc, hẹp quyền, lấy lười tại lúc gọi (không tạo gì khi import):
  event_db  -> events, event_showtimes
  ticket_db -> ticket_types, seat_layouts, seats, seat_rows, seat_zones
Lỗi cơ sở dữ liệu không bị biến thành kết quả thành công: chúng ném AiError (AI_DB_NOT_CONFIGURED / AI_UNAVAILABLE).
"""
import json
import logging
import re
from datetime import datetime, timezone, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional

from langchain_core.tools import tool
from sqlalchemy import bindparam, text
from sqlalchemy.exc import SQLAlchemyError

from app.core.errors import AiError, ai_error, AI_UNAVAILABLE
from app.db.engines import get_chatbot_engine
from app.services.ai.customer_sql import VALID_EVENT_FILTER

log = logging.getLogger("ai.tools")

VN_TZ = timezone(timedelta(hours=7))


def to_naive_vn_datetime(dt):
    """Chuyển datetime hoặc chuỗi ISO về giờ Việt Nam (UTC+7) không kèm múi giờ; None nếu rỗng hoặc chuỗi không đọc được."""
    if dt is None:
        return None
    if isinstance(dt, str):
        try:
            dt = datetime.fromisoformat(dt.replace("Z", "+00:00"))
        except Exception:
            return None
    if hasattr(dt, "tzinfo") and dt.tzinfo is not None:
        return dt.astimezone(VN_TZ).replace(tzinfo=None)
    return dt


def event_db():
    """Engine chỉ đọc của event_db (tạo ở lần dùng đầu)."""
    return get_chatbot_engine("event")


def ticket_db():
    """Engine chỉ đọc của ticket_db (tạo ở lần dùng đầu)."""
    return get_chatbot_engine("ticket")


def _db_unavailable(failure: Exception) -> AiError:
    """Ghi log lỗi cơ sở dữ liệu (chỉ tên lỗi) và trả AiError AI_UNAVAILABLE để nơi gọi ném tiếp."""
    log.warning("chatbot tool database error: %s", type(failure).__name__)
    return ai_error(AI_UNAVAILABLE)


# mô hình quyết định danh sách id nên số lượng bị giới hạn
MAX_EVENT_CARDS = 10
MAX_SEATS_PER_REQUEST = 10
MAX_TICKETS_PER_ORDER = 20

SEAT_MAP_RECHECK_NOTE = (
    "Lưu ý: ghế đang được người khác giữ chỗ tạm thời không hiển thị cho trợ lý, "
    "vui lòng kiểm tra lại trên sơ đồ ghế trước khi đặt."
)


# ghế
def _natural_key(value) -> list:
    """Khóa sắp xếp tự nhiên không bao giờ so sánh int với str: '2' < '10' < 'A1' < 'B2'."""
    parts = re.split(r"(\d+)", str(value))
    return [(0, int(p), "") if p.isdigit() else (1, 0, p.lower()) for p in parts if p != ""]


def _as_int(value) -> Optional[int]:
    """Đổi giá trị thành int nếu là số nguyên thật (kể cả float nguyên hoặc chuỗi số); bool và giá trị khác trả None."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


def _seat_number(seat: dict):
    """Lấy số ghế từ khóa `number`, nếu thiếu thì dùng `col`."""
    number = seat.get("number")
    if number is None or number == "":
        number = seat.get("col")
    return number


def _zone_list(zones) -> List[Dict[str, Any]]:
    """Danh sách [{'id','name','tier'}] từ sơ đồ mà zones là dict (id -> khu) hoặc list."""
    out = []
    if isinstance(zones, dict):
        for zid, zobj in zones.items():
            zobj = zobj if isinstance(zobj, dict) else {"name": str(zobj)}
            out.append({"id": str(zid), "name": str(zobj.get("name") or zid), "tier": zobj.get("tierId")})
    elif isinstance(zones, list):
        for idx, zobj in enumerate(zones):
            if isinstance(zobj, dict):
                zid = zobj.get("id", idx)
                out.append({"id": str(zid), "name": str(zobj.get("name") or zid), "tier": zobj.get("tierId")})
    return out


def _match_zone(zones: List[Dict[str, Any]], wanted: str) -> Optional[Dict[str, Any]]:
    """Tìm khu theo tên: ưu tiên khớp chính xác, sau đó khớp chứa nhau (không phân biệt hoa thường); None nếu không có."""
    w = (wanted or "").strip().lower()
    if not w:
        return None
    for z in zones:
        if z["name"].strip().lower() == w:
            return z
    for z in zones:
        n = z["name"].strip().lower()
        if n and (w in n or n in w):
            return z
    return None


def _json(payload: dict) -> str:
    """Chuyển dict thành chuỗi JSON giữ nguyên ký tự tiếng Việt."""
    return json.dumps(payload, ensure_ascii=False)


@tool
def find_adjacent_seats(event_id: str, zone: str, num_seats: int) -> str:
    """
    Tìm các ghế trống nằm cạnh nhau (cùng khu vực, cùng hàng, số ghế liên tiếp) trong một khu vực (zone) của một sự kiện
    dựa trên sơ đồ ghế trong cơ sở dữ liệu. Nếu không có đủ ghế liền kề, kết quả có status 'no_adjacent_seats'
    (kèm gợi ý ghế gần nhau nhưng KHÔNG liền kề, được ghi rõ).

    Args:
        event_id: Mã sự kiện (Ví dụ: "xoay-tron-concert-hoang-dung")
        zone: Tên khu vực muốn ngồi (Ví dụ: "VIP", "Standard", "Khu A")
        num_seats: Số lượng ghế liền kề cần tìm (Ví dụ: 2, 3, 4)
    """
    n = _as_int(num_seats)
    if n is None or n < 1 or n > MAX_SEATS_PER_REQUEST:
        return _json({"status": "invalid_request", "event_id": event_id,
                      "message": f"Số ghế cần tìm phải từ 1 đến {MAX_SEATS_PER_REQUEST}."})
    zone = zone or ""

    try:
        with ticket_db().connect() as conn:
            layout_row = conn.execute(text('''
                SELECT id, draft_json, image_url
                FROM seat_layouts
                WHERE event_id = :eid
                ORDER BY (status = 'PUBLISHED') DESC, updated_at DESC NULLS LAST
                LIMIT 1
            '''), {"eid": event_id}).fetchone()

            if not layout_row or not layout_row.draft_json:
                return _general_admission(conn, event_id, zone, n)

            try:
                data = json.loads(layout_row.draft_json)
                if not isinstance(data, dict):
                    raise ValueError("layout is not an object")
            except ValueError:
                return _json({"status": "error", "event_id": event_id,
                              "message": "Không đọc được dữ liệu sơ đồ ghế của sự kiện này."})

            seats = [s for s in (data.get("seats") or []) if isinstance(s, dict)]
            zones = _zone_list(data.get("zones"))
            tier_price = {}
            for t in data.get("ticketTiers") or []:
                if isinstance(t, dict) and "id" in t:
                    try:
                        tier_price[t["id"]] = float(t.get("price", 0) or 0)
                    except (TypeError, ValueError):
                        pass

            target = _match_zone(zones, zone)
            if zone.strip() and target is None and zones:
                return _json({
                    "status": "zone_not_found", "event_id": event_id,
                    "available_zones": [z["name"] for z in zones][:30],
                    "message": f"Không tìm thấy khu vực '{zone.strip()[:60]}' trong sự kiện này.",
                })
            zone_name_by_id = {z["id"]: z for z in zones}

            unavailable = _unavailable_from_table(conn, layout_row.id)

            groups: Dict[tuple, list] = {}
            for s in seats:
                if str(s.get("status", "available")).lower() != "available":
                    continue
                zid = str(s.get("zoneId")) if s.get("zoneId") is not None else ""
                if target is not None and zid != target["id"]:
                    continue
                z = zone_name_by_id.get(zid)
                zname = z["name"] if z else zid
                row = str(s.get("row") if s.get("row") is not None else "")
                number = _seat_number(s)
                if number is None:
                    continue
                if (zname.strip().lower(), row.strip().lower(), str(number).strip().lower()) in unavailable:
                    continue  # bảng seats cho biết ghế đã bị chiếm
                groups.setdefault((zid, row), []).append((number, _as_int(number), s, zname, z))

            if not groups:
                return _json({"status": "no_seats", "event_id": event_id,
                              "zone": target["name"] if target else (zone.strip() or None),
                              "message": "Khu vực này hiện không còn ghế trống. " + SEAT_MAP_RECHECK_NOTE})

            zone_order = {z["id"]: i for i, z in enumerate(zones)}
            ordered_keys = sorted(groups, key=lambda k: (zone_order.get(k[0], 10**6), _natural_key(k[1])))

            for key in ordered_keys:
                run = _adjacent_run(groups[key], n)
                if run:
                    return _success_adjacent(conn, event_id, key, run, tier_price, n)

            return _no_adjacent(event_id, target, groups, ordered_keys, n)
    except AiError:
        raise
    except SQLAlchemyError as failure:
        raise _db_unavailable(failure)


def _unavailable_from_table(conn, layout_id) -> set:
    """Các ghế mà bảng `seats` đánh dấu không còn AVAILABLE, khóa (tên khu, hàng, số ghế) viết thường. Rỗng khi sơ đồ
    không có dòng nào trong bảng (khi đó bản nháp là nguồn duy nhất)."""
    rows = conn.execute(text('''
        SELECT sz.name AS zone_name, sr.label AS row_label, s.seat_number AS seat_number, s.seat_status AS seat_status
        FROM seats s
        JOIN seat_rows sr ON sr.id = s.seat_row_id
        JOIN seat_zones sz ON sz.id = sr.seat_zone_id
        WHERE sz.seat_layout_id = :lid
    '''), {"lid": layout_id}).fetchall()
    taken = set()
    for r in rows:
        if str(r.seat_status).upper() != "AVAILABLE":
            taken.add((str(r.zone_name or "").strip().lower(), str(r.row_label or "").strip().lower(),
                       str(r.seat_number).strip().lower()))
    return taken


def _adjacent_run(entries: list, n: int) -> Optional[list]:
    """n ghế của MỘT (khu, hàng) có số nguyên liên tiếp, hoặc None. Số ghế không phải số nguyên không bao giờ tạo thành dãy."""
    numeric = {}
    for number, as_int, seat, zname, z in entries:
        if as_int is not None and as_int not in numeric:
            numeric[as_int] = (number, seat, zname, z)
    ordered = sorted(numeric)
    for i in range(len(ordered) - n + 1):
        if ordered[i + n - 1] - ordered[i] == n - 1:
            return [numeric[v] for v in ordered[i:i + n]]
    return None


def _general_admission(conn, event_id: str, zone: str, n: int) -> str:
    """Trả lời cho sự kiện vé tự do (không có sơ đồ ghế): chọn hạng vé ACTIVE khớp tên khu (mặc định hạng rẻ nhất) và tính tổng tiền."""
    types = conn.execute(text('''
        SELECT name, price
        FROM ticket_types
        WHERE event_id = :eid AND ticket_type_status = 'ACTIVE'
        ORDER BY price ASC
    '''), {"eid": event_id}).fetchall()
    if not types:
        return _json({"status": "not_found", "event_id": event_id,
                      "message": "Không tìm thấy dữ liệu sơ đồ ghế hoặc hạng vé cho sự kiện này."})
    wanted = zone.strip().lower()
    matched = next((t for t in types if wanted and wanted in t.name.lower()), types[0])
    price = float(matched.price)
    return _json({
        "status": "general_admission",
        "event_id": event_id,
        "zone": matched.name,
        "quantity": n,
        "price_per_seat": price,
        "total_price": price * n,
        "message": f"Sự kiện này áp dụng vé tự do (không đánh số ghế cố định). Bạn có thể đặt {n} vé hạng '{matched.name}'.",
    })


def _success_adjacent(conn, event_id, key, run, tier_price, n) -> str:
    """Dựng kết quả thành công khi tìm được dãy ghế liền kề; giá lấy từ hạng vé của sơ đồ, thiếu thì lấy hạng ACTIVE rẻ nhất."""
    zid, row = key
    first_number, first_seat, zname, zobj = run[0]
    tier_id = first_seat.get("tierId") or (zobj or {}).get("tier")
    price = tier_price.get(tier_id, 0)
    if not price:
        prow = conn.execute(text(
            "SELECT price FROM ticket_types WHERE event_id = :eid AND ticket_type_status = 'ACTIVE' ORDER BY price ASC LIMIT 1"
        ), {"eid": event_id}).fetchone()
        if prow:
            price = float(prow.price)
    labels = [f"{row}{number}" for number, _, _, _ in run]
    return _json({
        "status": "success",
        "event_id": event_id,
        "zone": zname,
        "row": row,
        "seats": labels,
        "seat_ids": [seat.get("id") for _, seat, _, _ in run],
        "num_seats": n,
        "adjacent": True,
        "price_per_seat": price,
        "total_price": price * n,
        "message": f"Đã tìm thấy {n} ghế liền kề tại Hàng {row} ({', '.join(labels)}) thuộc khu vực {zname}. "
                   + SEAT_MAP_RECHECK_NOTE,
    })


def _no_adjacent(event_id, target, groups, ordered_keys, n) -> str:
    """Dựng kết quả khi không có đủ ghế liền kề: gợi ý các ghế trống gần nhau ở hàng nhiều ghế nhất và ghi rõ là KHÔNG liền kề."""
    best = max(ordered_keys, key=lambda k: len(groups[k]))  # hàng đầu tiên trong các hàng đầy nhất
    entries = sorted(groups[best], key=lambda e: _natural_key(e[0]))[:n]
    zname = entries[0][3] if entries else (target["name"] if target else "")
    labels = [f"{best[1]}{e[0]}" for e in entries]
    return _json({
        "status": "no_adjacent_seats",
        "event_id": event_id,
        "zone": target["name"] if target else None,
        "num_seats": n,
        "nearby_not_adjacent": {"adjacent": False, "zone": zname, "row": best[1], "seats": labels},
        "message": f"Không còn {n} ghế liền kề nhau (cùng hàng, số liên tiếp). Các ghế trống gần nhau dưới đây "
                   f"KHÔNG liền kề: {', '.join(labels)}. " + SEAT_MAP_RECHECK_NOTE,
    })


# xác nhận (giá được tính tại đây)
def _money(value) -> Optional[Decimal]:
    """Đổi giá trị thành Decimal; None nếu không hợp lệ."""
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _norm_name(value) -> str:
    """Chuẩn hóa tên để so sánh: chữ thường, gộp khoảng trắng."""
    return " ".join(str(value or "").lower().split())


def _confirmation_error(message: str) -> str:
    """Dựng JSON báo lỗi cho thao tác BOOK_TICKETS."""
    return _json({"status": "error", "action_type": "BOOK_TICKETS", "message": message})


@tool
def request_user_confirmation(action_type: str, data: str) -> str:
    """
    Sử dụng công cụ này khi bạn cần NGƯỜI DÙNG XÁC NHẬN MỘT THAO TÁC GHI (như Đặt Vé, Thanh Toán).
    Bạn (AI) không có quyền tự ghi dữ liệu. Mọi thao tác ghi phải thông qua người dùng.
    Giá và tổng tiền do HỆ THỐNG tự tính từ bảng giá thật; mọi con số giá bạn đưa vào đều bị bỏ qua.

    Args:
        action_type: Loại thao tác (BẮT BUỘC là "BOOK_TICKETS")
        data: Chuỗi JSON: {"event_id": "...", "showtime_id": "...", "tickets": [{"ticket_type_id": "...", "tier_name": "...", "quantity": 2}], "seats": ["A1", "A2"]}
    """
    if str(action_type).strip().upper() != "BOOK_TICKETS":
        return _confirmation_error("Loại thao tác không được hỗ trợ.")
    try:
        payload = json.loads(data) if isinstance(data, str) else data
    except ValueError:
        return _confirmation_error("Dữ liệu xác nhận không hợp lệ.")
    if not isinstance(payload, dict):
        return _confirmation_error("Dữ liệu xác nhận không hợp lệ.")

    event_id = payload.get("event_id")
    lines = payload.get("tickets")
    if not isinstance(event_id, str) or not event_id.strip() or not isinstance(lines, list) or not lines:
        return _confirmation_error("Thiếu mã sự kiện hoặc danh sách vé cần đặt.")
    if len(lines) > 10:
        return _confirmation_error("Quá nhiều loại vé trong một đơn.")
    event_id = event_id.strip()

    try:
        with event_db().connect() as conn:
            ev = conn.execute(text(f"SELECT e.id, e.title FROM events e WHERE e.id = :eid AND {VALID_EVENT_FILTER}"),
                              {"eid": event_id}).fetchone()
            showtimes = conn.execute(text("SELECT id, name FROM event_showtimes WHERE event_id = :eid"),
                                     {"eid": event_id}).fetchall()
        if not ev:
            return _confirmation_error("Không tìm thấy sự kiện hoặc sự kiện không còn mở bán.")
        with ticket_db().connect() as conn:
            types = conn.execute(text('''
                SELECT id, showtime_id, name, price, ticket_type_status, min_per_order, max_per_order
                FROM ticket_types
                WHERE event_id = :eid AND ticket_type_status = 'ACTIVE'
            '''), {"eid": event_id}).fetchall()
    except AiError:
        raise
    except SQLAlchemyError as failure:
        raise _db_unavailable(failure)

    wanted_showtime = str(payload.get("showtime_id") or "").strip()
    out_lines, total, total_qty, showtime_ids = [], Decimal(0), 0, set()
    for line in lines:
        if not isinstance(line, dict):
            return _confirmation_error("Dòng vé không hợp lệ.")
        qty = _as_int(line.get("quantity"))
        if qty is None or qty < 1 or qty > MAX_TICKETS_PER_ORDER:
            return _confirmation_error(f"Số lượng vé phải từ 1 đến {MAX_TICKETS_PER_ORDER}.")
        tid = str(line.get("ticket_type_id") or line.get("id") or "").strip()
        name = _norm_name(line.get("tier_name") or line.get("name"))
        candidates = [t for t in types if tid and str(t.id) == tid]
        if not candidates and name:
            candidates = [t for t in types if _norm_name(t.name) == name]
            if wanted_showtime and len(candidates) > 1:
                candidates = [t for t in candidates if t.showtime_id is None or str(t.showtime_id) == wanted_showtime]
        if not candidates:
            return _confirmation_error("Không tìm thấy hạng vé đã chọn trong hệ thống. Vui lòng chọn lại hạng vé.")
        if len(candidates) > 1:
            return _confirmation_error("Hạng vé trùng tên ở nhiều suất diễn. Vui lòng chọn rõ suất diễn.")
        t = candidates[0]
        price = _money(t.price)
        if price is None or price < 0:
            return _confirmation_error("Giá vé của hạng này không hợp lệ.")
        if (t.min_per_order and qty < t.min_per_order) or (t.max_per_order and qty > t.max_per_order):
            return _confirmation_error(
                f"Hạng vé '{t.name}' chỉ cho đặt từ {t.min_per_order or 1} đến {t.max_per_order or MAX_TICKETS_PER_ORDER} vé mỗi đơn.")
        subtotal = price * qty
        total += subtotal
        total_qty += qty
        if t.showtime_id:
            showtime_ids.add(str(t.showtime_id))
        out_lines.append({"ticket_type_id": str(t.id), "tier_name": t.name, "quantity": qty,
                          "price": float(price), "subtotal": float(subtotal)})

    seats = payload.get("seats")
    if seats is None:
        seats = []
    if not isinstance(seats, list) or any(not isinstance(s, str) or not s.strip() or len(s) > 20 for s in seats):
        return _confirmation_error("Danh sách ghế không hợp lệ.")
    if seats and len(seats) != total_qty:
        return _confirmation_error("Số ghế không khớp với số lượng vé.")

    showtime_id = None
    if len(showtime_ids) == 1:
        showtime_id = next(iter(showtime_ids))
    elif wanted_showtime and any(str(s.id) == wanted_showtime for s in showtimes):
        showtime_id = wanted_showtime
    showtime_name = next((s.name for s in showtimes if showtime_id and str(s.id) == showtime_id), None)

    confirmed = {
        "event_id": ev.id,
        "event_title": ev.title,
        "showtime_id": showtime_id,
        "showtime_name": showtime_name,
        "tickets": out_lines,
        "seats": [s.strip() for s in seats],
        "total_price": float(total),
        "prices_source": "server",
    }
    return _json({"status": "waiting_for_user", "action_type": "BOOK_TICKETS", "data": _json(confirmed)})


# thẻ sự kiện
@tool
def show_event_cards(event_ids: List[str]) -> str:
    """
    Sử dụng công cụ này ĐỂ HIỂN THỊ THẺ SỰ KIỆN (CARDS) (Thuộc Bước 1).
    TUYỆT ĐỐI KHÔNG GỌI CÔNG CỤ NÀY NẾU NGƯỜI DÙNG ĐÃ CHỌN SỰ KIỆN HOẶC ĐÃ CUNG CẤP MÃ SỰ KIỆN.

    Args:
        event_ids: Danh sách (List) chứa các MÃ SỰ KIỆN (event_id) mà bạn muốn hiển thị.
    """
    wanted = []
    for eid in event_ids or []:
        if isinstance(eid, str) and eid.strip() and eid.strip() not in wanted:
            wanted.append(eid.strip())
    wanted = wanted[:MAX_EVENT_CARDS]
    cards_data = []
    if wanted:
        try:
            with event_db().connect() as conn:
                rows = conn.execute(
                    text(f'''
                        SELECT e.id, e.title, e.banner_url, e.thumbnail_url, e.location_name, e.city, e.dates_summary, e.min_price
                        FROM events e
                        WHERE e.id IN :eids AND {VALID_EVENT_FILTER}
                    ''').bindparams(bindparam("eids", expanding=True)),
                    {"eids": wanted},
                ).fetchall()
            by_id = {r.id: r for r in rows}
            min_prices = {}
            if by_id:
                with ticket_db().connect() as conn:
                    for r in conn.execute(
                        text("SELECT event_id, MIN(price) AS min_price FROM ticket_types "
                             "WHERE event_id IN :eids AND ticket_type_status != 'HIDDEN' GROUP BY event_id"
                             ).bindparams(bindparam("eids", expanding=True)),
                        {"eids": list(by_id)},
                    ).fetchall():
                        if r.min_price is not None:
                            min_prices[r.event_id] = float(r.min_price)
        except AiError:
            raise
        except SQLAlchemyError as failure:
            raise _db_unavailable(failure)

        for eid in wanted:
            result = by_id.get(eid)
            if not result:
                continue
            min_price_val = min_prices.get(eid, result.min_price)
            cards_data.append({
                "event_id": result.id,
                "title": result.title,
                "banner_url": result.banner_url or result.thumbnail_url or "",
                "min_price": float(min_price_val) if min_price_val is not None else 0,
                "location": f"{result.location_name or ''}, {result.city or ''}".strip(", "),
                "dates_summary": result.dates_summary or ""
            })

    return json.dumps({"status": "show_cards", "action_type": "SHOW_EVENT_CARDS", "data": json.dumps(cards_data, ensure_ascii=False)}, ensure_ascii=False)


@tool
def show_seatmap(event_id: str) -> str:
    """
    Sử dụng công cụ này BẮT BUỘC khi người dùng muốn xem sơ đồ ghế (seatmap), chọn ghế, hoặc hỏi về vị trí khu vực sự kiện.

    Args:
        event_id: Mã sự kiện (Ví dụ: "1ad0cfc7-6ec7-4af8-a38c-a621882ea4d6")
    """
    seatmap_url = None
    has_canvas = False
    tiers_info = []

    try:
        with event_db().connect() as conn:
            result = conn.execute(text(f"SELECT e.title FROM events e WHERE e.id = :eid AND {VALID_EVENT_FILTER}"),
                                  {"eid": event_id}).fetchone()
        if not result:
            return _json({"status": "not_found", "event_id": event_id,
                          "message": "Không tìm thấy sự kiện hoặc sự kiện không còn mở bán."})
        event_title = result.title or ""

        with ticket_db().connect() as conn:
            layout = conn.execute(text("""
                SELECT id, image_url, status, LENGTH(draft_json) as dlen
                FROM seat_layouts
                WHERE event_id = :eid
                ORDER BY (status = 'PUBLISHED') DESC, updated_at DESC NULLS LAST
                LIMIT 1
            """), {"eid": event_id}).fetchone()
            if layout:
                if layout.dlen and layout.dlen > 50:
                    has_canvas = True
                if layout.image_url:
                    seatmap_url = layout.image_url

            for tt in conn.execute(text("""
                SELECT id, name, price
                FROM ticket_types
                WHERE event_id = :eid AND ticket_type_status != 'HIDDEN'
                ORDER BY price ASC
            """), {"eid": event_id}).fetchall():
                tiers_info.append({
                    "id": str(tt.id),
                    "name": tt.name,
                    "price": float(tt.price) if tt.price else 0
                })
    except AiError:
        raise
    except SQLAlchemyError as failure:
        raise _db_unavailable(failure)

    return json.dumps({
        "status": "success",
        "action_type": "SHOW_SEATMAP",
        "event_id": event_id,
        "event_title": event_title,
        "has_canvas": has_canvas,
        "seatmap_image_url": seatmap_url,
        "tiers": tiers_info
    }, ensure_ascii=False)

@tool
def show_ticket_types(event_id: str) -> str:
    """
    BẮT BUỘC GỌI CÔNG CỤ NÀY NGAY LẬP TỨC (BƯỚC 2) khi người dùng vừa CHỌN một sự kiện hoặc CUNG CẤP MÃ SỰ KIỆN.
    Công cụ này sẽ lấy danh sách các SUẤT DIỄN, TRẠNG THÁI SUẤT DIỄN và HẠNG VÉ thực tế mới nhất từ cơ sở dữ liệu.
    Đồng thời phân biệt sự kiện có sơ đồ ghế (seatmap) hay không, và phân biệt khu vực có ghế ngồi (SEATED) vs khu vực đứng (STANDING).
    
    Args:
        event_id: Mã sự kiện (Ví dụ: "xoay-tron-concert-hoang-dung")
    """
    try:
        return _ticket_types_payload(event_id)
    except AiError:
        raise
    except SQLAlchemyError as failure:
        raise _db_unavailable(failure)


def _ticket_types_payload(event_id: str) -> str:
    """Dựng JSON danh sách suất diễn, trạng thái bán và hạng vé (kèm cờ ghế ngồi/khu đứng) của một sự kiện từ event_db và ticket_db."""
    # lấy thông tin sự kiện từ event_db
    with event_db().connect() as conn:
        ev_query = text("""
            SELECT id, title, banner_url, thumbnail_url, location_name, city, 
                   start_time, end_time, sale_start_time, sale_end_time
            FROM events
            WHERE id = :eid
        """)
        ev = conn.execute(ev_query, {"eid": event_id}).fetchone()
        if not ev:
            return json.dumps({
                "status": "error", 
                "message": f"Không tìm thấy thông tin sự kiện '{event_id}'."
            }, ensure_ascii=False)

        # lấy các suất diễn từ event_db
        st_query = text("""
            SELECT id, name, start_time, end_time, sale_start_time, sale_end_time
            FROM event_showtimes
            WHERE event_id = :eid
            ORDER BY start_time ASC
        """)
        showtimes_rows = conn.execute(st_query, {"eid": event_id}).fetchall()

    # lấy các hạng vé từ ticket_db
    with ticket_db().connect() as conn:
        tt_query = text("""
            SELECT id, showtime_id, name, price, description, ticket_type_status, 
                   total_quantity, min_per_order, max_per_order
            FROM ticket_types
            WHERE event_id = :eid AND ticket_type_status != 'HIDDEN'
            ORDER BY price ASC
        """)
        ticket_rows = conn.execute(tt_query, {"eid": event_id}).fetchall()

        # lấy sơ đồ ghế từ ticket_db
        sl_query = text("""
            SELECT id, draft_json, image_url, status
            FROM seat_layouts
            WHERE event_id = :eid
            ORDER BY (status = 'PUBLISHED') DESC
            LIMIT 1
        """)
        layout_row = conn.execute(sl_query, {"eid": event_id}).fetchone()

    # xác định thông tin sơ đồ ghế và khu có ghế ngồi hay khu đứng
    has_seatmap = False
    seatmap_image_url = None
    tier_seated_map = {}

    if layout_row:
        seatmap_image_url = layout_row.image_url
        if layout_row.draft_json:
            try:
                layout_data = json.loads(layout_row.draft_json)
                zones = layout_data.get("zones", {})
                seats = layout_data.get("seats", [])
                
                if (isinstance(zones, dict) and len(zones) > 0) or len(seats) > 0:
                    has_seatmap = True
                
                for zid, z in (zones.items() if isinstance(zones, dict) else enumerate(zones)):
                    z_data = z if isinstance(z, dict) else {}
                    z_name = (z_data.get("name") or "").strip()
                    z_tier_id = z_data.get("tierId")
                    z_type = z_data.get("type")
                    
                    seats_in_z = [s for s in seats if s.get("zoneId") == zid or s.get("zoneId") == z_name]
                    is_seated = (len(seats_in_z) > 0) or (z_type == "SEATED")
                    
                    if z_tier_id:
                        tier_seated_map[z_tier_id] = is_seated
                    if z_name:
                        clean_z_name = z_name.lower().replace(" ", "").replace("-", "")
                        tier_seated_map[clean_z_name] = is_seated
            except Exception as e:
                log.warning("Could not parse the seat layout JSON: %s", type(e).__name__)

    now = datetime.now(VN_TZ).replace(tzinfo=None)
    
    # định dạng toàn bộ vé của sự kiện để truy cập trực tiếp
    all_event_tickets = []
    for t in ticket_rows:
        clean_t_name = t.name.lower().replace(" ", "").replace("-", "")
        is_seated = tier_seated_map.get(t.id, False)
        if not is_seated:
            for k, val in tier_seated_map.items():
                if k in clean_t_name or clean_t_name in k:
                    is_seated = val
                    break
        all_event_tickets.append({
            "id": t.id,
            "name": t.name,
            "price": float(t.price),
            "description": t.description or "",
            "status": t.ticket_type_status,
            "total_quantity": t.total_quantity,
            "min_per_order": t.min_per_order or 1,
            "max_per_order": t.max_per_order or 10,
            "is_seated": is_seated,
            "area_type": "Ghế ngồi (SEATED)" if is_seated else "Khu đứng / Tự do (STANDING)"
        })

    # xử lý các suất diễn và gắn vé tương ứng
    showtimes_list = []
    if showtimes_rows:
        for st in showtimes_rows:
            st_start = to_naive_vn_datetime(st.start_time)
            st_end = to_naive_vn_datetime(st.end_time)
            st_sale_start = to_naive_vn_datetime(st.sale_start_time or ev.sale_start_time)
            st_sale_end = to_naive_vn_datetime(st.sale_end_time or ev.sale_end_time or st.end_time)
            
            # khớp các vé thuộc suất diễn này
            st_id_str = str(st.id).strip().lower() if st.id else ""
            matched_tickets = [
                t for t in ticket_rows 
                if t.showtime_id is None or not str(t.showtime_id).strip() or str(t.showtime_id).strip().lower() == st_id_str
            ]
            if not matched_tickets and ticket_rows:
                matched_tickets = list(ticket_rows)
            
            # tính trạng thái suất diễn
            if st_sale_start and now < st_sale_start:
                st_status = "UPCOMING"
                st_label = "Sắp mở bán"
            elif st_sale_end and now > st_sale_end:
                st_status = "SALE_ENDED"
                st_label = "Ngừng bán vé online"
            elif matched_tickets and all(t.ticket_type_status == "SOLD_OUT" or (t.total_quantity is not None and t.total_quantity <= 0) for t in matched_tickets):
                st_status = "SOLD_OUT"
                st_label = "Hết vé"
            else:
                st_status = "ON_SALE"
                st_label = "Đang mở bán"

            formatted_tickets = []
            for t in matched_tickets:
                clean_t_name = t.name.lower().replace(" ", "").replace("-", "")
                is_seated = tier_seated_map.get(t.id, False)
                if not is_seated:
                    for k, val in tier_seated_map.items():
                        if k in clean_t_name or clean_t_name in k:
                            is_seated = val
                            break

                formatted_tickets.append({
                    "id": t.id,
                    "name": t.name,
                    "price": float(t.price),
                    "description": t.description or "",
                    "status": t.ticket_type_status,
                    "total_quantity": t.total_quantity,
                    "min_per_order": t.min_per_order or 1,
                    "max_per_order": t.max_per_order or 10,
                    "is_seated": is_seated,
                    "area_type": "Ghế ngồi (SEATED)" if is_seated else "Khu đứng / Tự do (STANDING)"
                })

            showtimes_list.append({
                "id": st.id,
                "name": st.name,
                "start_time": st_start.strftime("%H:%M, %d/%m/%Y") if st_start else "",
                "end_time": st_end.strftime("%H:%M, %d/%m/%Y") if st_end else "",
                "status": st_status,
                "status_label": st_label,
                "tickets": formatted_tickets
            })
    else:
        # dự phòng: một suất diễn duy nhất
        st_start = ev.start_time
        st_end = ev.end_time
        st_sale_start = ev.sale_start_time
        st_sale_end = ev.sale_end_time or ev.end_time
        
        if st_sale_start and now < st_sale_start:
            st_status = "UPCOMING"
            st_label = "Sắp mở bán"
        elif st_sale_end and now > st_sale_end:
            st_status = "SALE_ENDED"
            st_label = "Ngừng bán vé online"
        elif ticket_rows and all(t.ticket_type_status == "SOLD_OUT" or (t.total_quantity is not None and t.total_quantity <= 0) for t in ticket_rows):
            st_status = "SOLD_OUT"
            st_label = "Hết vé"
        else:
            st_status = "ON_SALE"
            st_label = "Đang mở bán"

        formatted_tickets = []
        for t in ticket_rows:
            clean_t_name = t.name.lower().replace(" ", "").replace("-", "")
            is_seated = tier_seated_map.get(t.id, False)
            if not is_seated:
                for k, val in tier_seated_map.items():
                    if k in clean_t_name or clean_t_name in k:
                        is_seated = val
                        break

            formatted_tickets.append({
                "id": t.id,
                "name": t.name,
                "price": float(t.price),
                "description": t.description or "",
                "status": t.ticket_type_status,
                "total_quantity": t.total_quantity,
                "min_per_order": t.min_per_order or 1,
                "max_per_order": t.max_per_order or 10,
                "is_seated": is_seated,
                "area_type": "Ghế ngồi (SEATED)" if is_seated else "Khu đứng / Tự do (STANDING)"
            })

        showtimes_list.append({
            "id": "st_default",
            "name": "Suất diễn chính",
            "start_time": st_start.strftime("%H:%M, %d/%m/%Y") if st_start else "",
            "end_time": st_end.strftime("%H:%M, %d/%m/%Y") if st_end else "",
            "status": st_status,
            "status_label": st_label,
            "tickets": formatted_tickets
        })

    payload = {
        "status": "success",
        "action_type": "SHOW_TICKET_TYPES",
        "event_id": ev.id,
        "event_title": ev.title,
        "banner_url": ev.banner_url or ev.thumbnail_url or "",
        "location": f"{ev.location_name or ''}, {ev.city or ''}".strip(", "),
        "has_seatmap": has_seatmap,
        "seatmap_image_url": seatmap_image_url or "",
        "tickets": all_event_tickets,
        "showtimes": showtimes_list
    }
    return json.dumps(payload, ensure_ascii=False)
