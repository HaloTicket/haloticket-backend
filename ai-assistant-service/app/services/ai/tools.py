from langchain_core.tools import tool
import json

@tool
def find_adjacent_seats(event_id: str, zone: str, num_seats: int) -> str:
    """
    Tìm các ghế trống nằm cạnh nhau trong một khu vực (zone) cụ thể của một sự kiện.
    
    Args:
        event_id: Mã sự kiện (Ví dụ: EV-001)
        zone: Khu vực muốn ngồi (Ví dụ: VIP, VVIP, Standard)
        num_seats: Số lượng ghế cần tìm (Ví dụ: 2, 3)
    """
    # Trong thực tế, bạn sẽ viết code gọi sang API của Booking-Service (Java)
    # Ở đây chúng ta sẽ giả lập (Mock) kết quả trả về từ DB
    print(f"[HỆ THỐNG] Đang truy xuất Database để tìm {num_seats} ghế khu {zone}...")
    
    # Giả sử tìm được 2 ghế cạnh nhau
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

