from langchain_core.tools import tool
import json
import os
from typing import List, Dict, Any
from datetime import datetime, timezone, timedelta
from sqlalchemy import create_engine, text

VN_TZ = timezone(timedelta(hours=7))

def to_naive_vn_datetime(dt):
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

EVENT_DB_URL = os.getenv("EVENT_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/event_db")
TICKET_DB_URL = os.getenv("TICKET_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/ticket_db")

event_engine = create_engine(EVENT_DB_URL)
ticket_engine = create_engine(TICKET_DB_URL)

@tool
def find_adjacent_seats(event_id: str, zone: str, num_seats: int) -> str:

    """
    Tìm các ghế trống nằm cạnh nhau trong một khu vực (zone) cụ thể của một sự kiện
    dựa trên sơ đồ ghế thực tế từ cơ sở dữ liệu.
    
    Args:
        event_id: Mã sự kiện (Ví dụ: "xoay-tron-concert-hoang-dung")
        zone: Tên khu vực muốn ngồi (Ví dụ: "VIP", "Standard", "Khu A")
        num_seats: Số lượng ghế liền kề cần tìm (Ví dụ: 2, 3, 4)
    """
    print(f"[HỆ THỐNG] Truy xuất Database tìm {num_seats} ghế liền kề khu '{zone}' cho sự kiện '{event_id}'...")
    
    try:
        # retrieve the seat map from ticket_db
        with ticket_engine.connect() as conn:
            query = text('''
                SELECT draft_json, image_url 
                FROM seat_layouts 
                WHERE event_id = :eid 
                ORDER BY (status = 'PUBLISHED') DESC, updated_at DESC NULLS LAST 
                LIMIT 1
            ''')
            layout_row = conn.execute(query, {"eid": event_id}).fetchone()

        if not layout_row or not layout_row.draft_json:
            # no seat map -> check general admission tickets
            with event_engine.connect() as conn:
                tquery = text('''
                    SELECT name, price 
                    FROM ticket_types 
                    WHERE event_id = :eid AND ticket_type_status = 'ACTIVE'
                    ORDER BY price ASC
                ''')
                types = conn.execute(tquery, {"eid": event_id}).fetchall()
                if types:
                    matched_type = next((t for t in types if zone.lower() in t.name.lower()), types[0])
                    return json.dumps({
                        "status": "general_admission",
                        "event_id": event_id,
                        "zone": matched_type.name,
                        "quantity": num_seats,
                        "price_per_seat": float(matched_type.price),
                        "total_price": float(matched_type.price) * num_seats,
                        "message": f"Sự kiện này áp dụng vé tự do (không đánh số ghế cố định). Bạn có thể đặt {num_seats} vé hạng '{matched_type.name}'."
                    }, ensure_ascii=False)
                else:
                    return json.dumps({
                        "status": "not_found",
                        "event_id": event_id,
                        "message": f"Không tìm thấy dữ liệu sơ đồ ghế hoặc hạng vé cho sự kiện '{event_id}'."
                    }, ensure_ascii=False)

        # parse layout_data from JSON
        data = json.loads(layout_row.draft_json)
        seats = data.get("seats", [])
        zones = data.get("zones", {})
        tiers_list = data.get("ticketTiers", [])
        tier_price_map = {t["id"]: float(t.get("price", 0)) for t in tiers_list if "id" in t}

        # find zoneId matching the requested zone name
        target_zone_id = None
        target_zone_name = zone
        target_tier_id = None

        if isinstance(zones, dict):
            for zid, zobj in zones.items():
                zname = zobj.get("name", "") if isinstance(zobj, dict) else str(zobj)
                if zone.lower() in zname.lower() or zname.lower() in zone.lower():
                    target_zone_id = zid
                    target_zone_name = zname
                    target_tier_id = zobj.get("tierId") if isinstance(zobj, dict) else None
                    break
        elif isinstance(zones, list):
            for zobj in zones:
                zname = zobj.get("name", "")
                if zone.lower() in zname.lower() or zname.lower() in zone.lower():
                    target_zone_id = zobj.get("id")
                    target_zone_name = zname
                    target_tier_id = zobj.get("tierId")
                    break

        # filter available seats in zone
        available_seats = []
        for s in seats:
            seat_status = s.get("status", "available")
            if seat_status != "available":
                continue
            
            # match zoneId if found, or collect all available seats if zone is general
            if target_zone_id and s.get("zoneId") != target_zone_id:
                continue
                
            available_seats.append(s)

        if not available_seats:
            return json.dumps({
                "status": "no_seats",
                "event_id": event_id,
                "zone": target_zone_name,
                "message": f"Khu vực '{target_zone_name}' hiện đã hết ghế trống."
            }, ensure_ascii=False)

        # group seats by row and find consecutive seats
        rows_map: Dict[str, List[Dict[str, Any]]] = {}
        for s in available_seats:
            row_key = s.get("row", "A")
            rows_map.setdefault(row_key, []).append(s)

        found_consecutive = None
        found_row = None

        for row_key, row_seats in rows_map.items():
            # sort by seat number
            row_seats.sort(key=lambda x: (x.get("number") or x.get("col") or 0))
            
            # find num_seats consecutive seats
            for i in range(len(row_seats) - num_seats + 1):
                window = row_seats[i : i + num_seats]
                numbers = [w.get("number") or w.get("col") or 0 for w in window]
                
                # check if seat numbers are consecutive
                is_consecutive = all(numbers[j] == numbers[j-1] + 1 for j in range(1, len(numbers)))
                if is_consecutive or len(numbers) == 1:
                    found_consecutive = window
                    found_row = row_key
                    break
            
            if found_consecutive:
                break

        # if no perfect consecutive seats found, take the nearest seats in the same row
        if not found_consecutive:
            for row_key, row_seats in rows_map.items():
                if len(row_seats) >= num_seats:
                    found_consecutive = row_seats[:num_seats]
                    found_row = row_key
                    break

        if not found_consecutive:
            return json.dumps({
                "status": "no_adjacent",
                "event_id": event_id,
                "zone": target_zone_name,
                "message": f"Khu vực '{target_zone_name}' hiện không còn đủ {num_seats} ghế liền kề nhau."
            }, ensure_ascii=False)

        # calculate actual ticket price
        first_seat = found_consecutive[0]
        seat_tier_id = first_seat.get("tierId") or target_tier_id
        price_per_seat = tier_price_map.get(seat_tier_id, 0)

        # if no price in layout_data, query price from ticket_types table
        if price_per_seat == 0:
            with event_engine.connect() as conn:
                pquery = text("SELECT price FROM ticket_types WHERE event_id = :eid ORDER BY price ASC LIMIT 1")
                prow = conn.execute(pquery, {"eid": event_id}).fetchone()
                if prow:
                    price_per_seat = float(prow.price)

        seat_labels = [f"{found_row}{s.get('number') or s.get('col')}" for s in found_consecutive]
        
        result = {
            "status": "success",
            "event_id": event_id,
            "zone": target_zone_name,
            "row": found_row,
            "seats": seat_labels,
            "seat_ids": [s.get("id") for s in found_consecutive],
            "num_seats": num_seats,
            "price_per_seat": price_per_seat,
            "total_price": price_per_seat * num_seats,
            "message": f"Đã tìm thấy {num_seats} ghế liền kề tại Hàng {found_row} ({', '.join(seat_labels)}) thuộc khu vực {target_zone_name}."
        }
        return json.dumps(result, ensure_ascii=False)

    except Exception as e:
        print(f"[LỖI find_adjacent_seats] {e}")
        return json.dumps({
            "status": "error",
            "event_id": event_id,
            "zone": zone,
            "message": f"Đã xảy ra lỗi khi tìm ghế: {str(e)}"
        }, ensure_ascii=False)

@tool
def search_events(keyword: str = "") -> str:
    """
    Sử dụng công cụ này BẮT BUỘC khi người dùng hỏi về danh sách sự kiện, tìm kiếm sự kiện, có sự kiện gì hot, v.v.
    Args:
        keyword: Từ khóa tìm kiếm (Ví dụ: "âm nhạc", "sự kiện gần đây"). Nếu người dùng hỏi chung chung, hãy truyền chuỗi rỗng "".
    """
    return "SEARCH_EVENTS"

@tool
def request_user_confirmation(action_type: str, data: str) -> str:
    """
    Sử dụng công cụ này khi bạn cần NGƯỜI DÙNG XÁC NHẬN MỘT THAO TÁC GHI (như Đặt Vé, Thanh Toán). 
    Bạn (AI) không có quyền tự ghi dữ liệu. Mọi thao tác ghi phải thông qua người dùng.
    
    Args:
        action_type: Loại thao tác (BẮT BUỘC là "BOOK_TICKETS")
        data: Dữ liệu cần xác nhận (JSON string chứa event_id, event_title, seats, tier_name, quantity, total_price, v.v.)
    """
    return json.dumps({"status": "waiting_for_user", "action_type": action_type, "data": data}, ensure_ascii=False)

@tool
def show_event_cards(event_ids: List[str]) -> str:
    """
    Sử dụng công cụ này ĐỂ HIỂN THỊ THẺ SỰ KIỆN (CARDS) (Thuộc Bước 1).
    TUYỆT ĐỐI KHÔNG GỌI CÔNG CỤ NÀY NẾU NGƯỜI DÙNG ĐÃ CHỌN SỰ KIỆN HOẶC ĐÃ CUNG CẤP MÃ SỰ KIỆN.
    
    Args:
        event_ids: Danh sách (List) chứa các MÃ SỰ KIỆN (event_id) mà bạn muốn hiển thị.
    """
    cards_data = []
    with event_engine.connect() as conn:
        for eid in event_ids:
            query = text('''
                SELECT e.id, e.title, e.banner_url, e.thumbnail_url, e.location_name, e.city, e.dates_summary,
                       COALESCE((SELECT MIN(price) FROM ticket_types WHERE event_id = e.id AND ticket_type_status = 'ACTIVE'), e.min_price, 0) as min_price
                FROM events e 
                WHERE e.id = :eid
                  AND (e.event_status IN ('LIVE', 'APPROVED') OR e.event_approval_status = 'APPROVED')
                  AND (e.end_time IS NULL OR e.end_time >= NOW())
            ''')
            result = conn.execute(query, {"eid": eid}).fetchone()
            if result:
                cards_data.append({
                    "event_id": result.id,
                    "title": result.title,
                    "banner_url": result.banner_url or result.thumbnail_url or "",
                    "min_price": float(result.min_price) if result.min_price else 0,
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
    event_title = ""
    tiers_info = []

    # 1. get event title from event_db
    try:
        with event_engine.connect() as conn:
            query = text("SELECT title FROM events WHERE id = :eid")
            result = conn.execute(query, {"eid": event_id}).fetchone()
            if result:
                event_title = result.title or ""
    except Exception as e:
        print(f"[LỖI show_seatmap event_db] {e}")

    # 2. check seat layout and ticket tiers from ticket_db
    try:
        with ticket_engine.connect() as conn:
            query = text("""
                SELECT id, image_url, status, LENGTH(draft_json) as dlen 
                FROM seat_layouts 
                WHERE event_id = :eid 
                ORDER BY (status = 'PUBLISHED') DESC 
                LIMIT 1
            """)
            result = conn.execute(query, {"eid": event_id}).fetchone()
            if result:
                if result.dlen and result.dlen > 50:
                    has_canvas = True
                if result.image_url:
                    seatmap_url = result.image_url

            # Also fetch ticket types for tiers summary
            tt_query = text("""
                SELECT id, name, price, description 
                FROM ticket_types 
                WHERE event_id = :eid AND ticket_type_status != 'HIDDEN'
                ORDER BY price ASC
            """)
            for tt in conn.execute(tt_query, {"eid": event_id}).fetchall():
                tiers_info.append({
                    "id": str(tt.id),
                    "name": tt.name,
                    "price": float(tt.price) if tt.price else 0
                })
    except Exception as e:
        print(f"[LỖI show_seatmap ticket_db] {e}")

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
    # fetch event info from event_db
    with event_engine.connect() as conn:
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

        # fetch showtimes from event_db
        st_query = text("""
            SELECT id, name, start_time, end_time, sale_start_time, sale_end_time
            FROM event_showtimes
            WHERE event_id = :eid
            ORDER BY start_time ASC
        """)
        showtimes_rows = conn.execute(st_query, {"eid": event_id}).fetchall()

    # fetch ticket types from ticket_db
    with ticket_engine.connect() as conn:
        tt_query = text("""
            SELECT id, showtime_id, name, price, description, ticket_type_status, 
                   total_quantity, min_per_order, max_per_order
            FROM ticket_types
            WHERE event_id = :eid AND ticket_type_status != 'HIDDEN'
            ORDER BY price ASC
        """)
        ticket_rows = conn.execute(tt_query, {"eid": event_id}).fetchall()

        # fetch seat layout from ticket_db
        sl_query = text("""
            SELECT id, draft_json, image_url, status
            FROM seat_layouts
            WHERE event_id = :eid
            ORDER BY (status = 'PUBLISHED') DESC
            LIMIT 1
        """)
        layout_row = conn.execute(sl_query, {"eid": event_id}).fetchone()

    # determine seatmap info & seated vs standing zones
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
                print("Error parsing layout JSON:", e)

    now = datetime.now(VN_TZ).replace(tzinfo=None)
    
    # process showtimes and associate tickets
    showtimes_list = []
    if showtimes_rows:
        for st in showtimes_rows:
            st_start = to_naive_vn_datetime(st.start_time)
            st_end = to_naive_vn_datetime(st.end_time)
            st_sale_start = to_naive_vn_datetime(st.sale_start_time or ev.sale_start_time)
            st_sale_end = to_naive_vn_datetime(st.sale_end_time or ev.sale_end_time or st.end_time)
            
            # match tickets belonging to this showtime
            matched_tickets = [t for t in ticket_rows if t.showtime_id == st.id or t.showtime_id is None]
            
            # calculate showtime status
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
        # fallback single showtime
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
        "showtimes": showtimes_list
    }
    return json.dumps(payload, ensure_ascii=False)
