from langchain_core.tools import tool
import json
from typing import List, Dict, Any

@tool
def find_adjacent_seats(event_id: str, zone: str, num_seats: int) -> str:
    """
    Tìm các ghế trống nằm cạnh nhau trong một khu vực (zone) cụ thể của một sự kiện.
    
    Args:
        event_id: Mã sự kiện (Ví dụ: EV-001)
        zone: Khu vực muốn ngồi (Ví dụ: VIP, VVIP, Standard)
        num_seats: Số lượng ghế cần tìm (Ví dụ: 2, 3)
    """
    print(f"[HỆ THỐNG] Đang truy xuất Database để tìm {num_seats} ghế khu {zone}...")
    
    mock_result = {
        "status": "success",
        "zone": zone,
        "seats": [f"{zone}-A12", f"{zone}-A13"] if num_seats == 2 else [f"{zone}-B1"],
        "price_per_seat": 1500000
    }
    return json.dumps(mock_result, ensure_ascii=False)

@tool
def search_events(keyword: str = "") -> str:
    """
    Sử dụng công cụ này BẮT BUỘC khi người dùng hỏi về danh sách sự kiện, tìm kiếm, có sự kiện gì hot, v.v.
    Args:
        keyword: Từ khóa tìm kiếm (Ví dụ: "âm nhạc", "sự kiện gần đây"). Nếu người dùng hỏi chung chung (vd: "tất cả sự kiện", "liệt kê sự kiện"), hãy truyền chuỗi rỗng "".
    """
    return "SEARCH_EVENTS"

@tool
def request_user_confirmation(action_type: str, data: str) -> str:
    """
    Sử dụng công cụ này khi bạn cần NGƯỜI DÙNG XÁC NHẬN MỘT THAO TÁC GHI (như Đặt Vé, Thanh Toán). 
    Bạn (AI) không có quyền tự ghi dữ liệu. Mọi thao tác ghi phải thông qua người dùng.
    
    Args:
        action_type: Loại thao tác (Ví dụ: "BOOK_TICKETS", "PROCEED_PAYMENT")
        data: Dữ liệu cần xác nhận (JSON string chứa chi tiết ghế, giá tiền, v.v.)
    """
    return json.dumps({"status": "waiting_for_user", "action_type": action_type, "data": data}, ensure_ascii=False)

@tool
def show_event_cards(event_ids: List[str]) -> str:
    """
    Sử dụng công cụ này ĐỂ HIỂN THỊ THẺ SỰ KIỆN (CARDS) (Thuộc Bước 1).
    TUYỆT ĐỐI KHÔNG GỌI CÔNG CỤ NÀY NẾU NGƯỜI DÙNG ĐÃ CHỌN SỰ KIỆN HOẶC ĐÃ CUNG CẤP MÃ SỰ KIỆN.
    Nếu người dùng đã chọn sự kiện, BẠN PHẢI chuyển sang BƯỚC 2 bằng cách gọi tool `show_ticket_types`.
    
    Args:
        event_ids: Danh sách (List) chứa các MÃ SỰ KIỆN (event_id) mà bạn muốn hiển thị. (VD: ["nxnx37-hoc-vien-phep-thuat", "xoay-tron-concert-hoang-dung"])
    """
    from sqlalchemy import create_engine, text
    import os
    
    EVENT_DB_URL = os.getenv("EVENT_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/event_db")
    engine = create_engine(EVENT_DB_URL)
    
    cards_data = []
    with engine.connect() as conn:
        for eid in event_ids:
            query = text('''
                SELECT e.id, e.title, e.banner_url, e.location_name, e.city,
                       COALESCE((SELECT MIN(price) FROM ticket_types WHERE event_id = e.id AND ticket_type_status = 'ACTIVE'), e.min_price, 0) as min_price
                FROM events e 
                WHERE e.id = :eid
            ''')
            result = conn.execute(query, {"eid": eid}).fetchone()
            if result:
                cards_data.append({
                    "event_id": result.id,
                    "title": result.title,
                    "banner_url": result.banner_url or "",
                    "min_price": float(result.min_price) if result.min_price else 0,
                    "location": f"{result.location_name or ''}, {result.city or ''}".strip(", ")
                })
                
    return json.dumps({"status": "show_cards", "action_type": "SHOW_EVENT_CARDS", "data": json.dumps(cards_data, ensure_ascii=False)}, ensure_ascii=False)

@tool
def show_seatmap(event_id: str) -> str:
    """
    Sử dụng công cụ này BẮT BUỘC khi người dùng muốn xem sơ đồ ghế (seatmap), chọn ghế, hoặc hỏi về sơ đồ sự kiện.
    
    Args:
        event_id: Mã sự kiện (Ví dụ: "nxnx37-hoc-vien-phep-thuat")
    """
    from sqlalchemy import create_engine, text
    import os
    
    EVENT_DB_URL = os.getenv("EVENT_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/event_db")
    engine = create_engine(EVENT_DB_URL)
    
    with engine.connect() as conn:
        query = text("SELECT seatmap_url FROM events WHERE id = :eid")
        result = conn.execute(query, {"eid": event_id}).fetchone()
        seatmap = result.seatmap_url if result and result.seatmap_url else "https://images.unsplash.com/photo-1540039155733-5bb30b53aa14?w=800&q=80"
                
    return json.dumps({"status": "success", "action_type": "SHOW_SEATMAP", "data": seatmap}, ensure_ascii=False)

@tool
def show_ticket_types(event_id: str) -> str:
    """
    BẮT BUỘC GỌI CÔNG CỤ NÀY NGAY LẬP TỨC (BƯỚC 2) khi người dùng vừa CHỌN một sự kiện hoặc CUNG CẤP MÃ SỰ KIỆN.
    Công cụ này sẽ lấy danh sách các HẠNG VÉ và GIÁ VÉ. 
    TUYỆT ĐỐI KHÔNG gọi `show_event_cards` khi đã có mã sự kiện.
    
    Args:
        event_id: Mã sự kiện (Ví dụ: "nxnx37-hoc-vien-phep-thuat")
    """
    from sqlalchemy import create_engine, text
    import os
    
    EVENT_DB_URL = os.getenv("EVENT_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/event_db")
    engine = create_engine(EVENT_DB_URL)
    
    ticket_types = []
    with engine.connect() as conn:
        query = text('''
            SELECT t.name, t.description, t.price, t.ticket_type_status, s.name as show_name
            FROM ticket_types t
            LEFT JOIN event_showtimes s ON t.showtime_id = s.id
            WHERE t.event_id = :eid 
              AND t.ticket_type_status != 'HIDDEN'
              AND (s.id IS NULL OR s.sale_start_time IS NULL OR s.sale_start_time <= NOW())
            ORDER BY s.sale_start_time ASC NULLS LAST, t.price ASC
        ''')
        results = conn.execute(query, {"eid": event_id}).fetchall()
        for r in results:
            ticket_types.append({
                "name": f"[{r.show_name}] {r.name}" if r.show_name else r.name,
                "description": r.description or "",
                "price": float(r.price),
                "status": r.ticket_type_status
            })
                
    return json.dumps({"status": "success", "action_type": "SHOW_TICKET_TYPES", "data": ticket_types}, ensure_ascii=False)
