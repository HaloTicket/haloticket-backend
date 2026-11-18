"""Tác vụ chạy tay: (xây lại) bảng event_vectors dùng cho tìm kiếm của chat khách hàng (RAG).

Đây là cách DUY NHẤT để điền event_vectors hiện nay (consumer Kafka từng làm việc này đã bị gỡ).
Nếu không chạy, tìm kiếm vector không có dữ liệu và chat-search dùng cách dự phòng theo từ khóa.

Việc nó làm: đọc các sự kiện APPROVED chưa hết bán vé (event_db) cùng suất diễn (event_db) và loại vé (ticket_db), dựng một
tài liệu JSON cho mỗi sự kiện, nhúng bằng mô hình embedding đã cấu hình (cần GEMINI_API_KEY; mỗi sự kiện một lần gọi embedding)
rồi upsert vào event_vectors (merge theo id). Chạy lại an toàn: dòng đã có bị ghi đè, không xóa gì (dòng của sự kiện đã kết thúc
còn nguyên cho tới khi xóa tay).

Biến môi trường bắt buộc (cố ý không có mặc định; script thoát kèm thông báo rõ khi thiếu):
  EVENT_DB_URL   URL SQLAlchemy của event_db,  ví dụ postgresql+psycopg2://<user>:<password>@postgres:5432/event_db
  TICKET_DB_URL  URL SQLAlchemy của ticket_db, ví dụ postgresql+psycopg2://<user>:<password>@postgres:5432/ticket_db
  GEMINI_API_KEY (do cấu hình ứng dụng đọc) cho mô hình embedding.
Tài khoản cần quyền ghi event_vectors trong event_db và quyền đọc các bảng trên. Container ai-assistant đã có sẵn
EVENT_DB_URL, TICKET_DB_URL và GEMINI_API_KEY từ docker-compose.

Chạy trong container (từ /app):
  docker exec -it haloticket-ai-assistant python sync_to_vectordb.py
Chạy trên máy (từ ai-assistant-service/, đã bật venv và export các biến):
  python sync_to_vectordb.py
"""
import os
import sys
import json
from datetime import datetime
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

def _require_env(name):
    """Đọc một biến môi trường bắt buộc.

    Raises:
        SystemExit: khi biến chưa đặt hoặc rỗng, kèm hướng dẫn đặt EVENT_DB_URL và TICKET_DB_URL.
    """
    value = os.getenv(name, "").strip()
    if not value:
        sys.exit(
            f"ERROR: environment variable {name} is not set. "
            "Set EVENT_DB_URL and TICKET_DB_URL (SQLAlchemy URLs, e.g. "
            "postgresql+psycopg2://<user>:<password>@<host>:5432/<db>) before running this script."
        )
    return value


if __name__ == "__main__":  # thoát sớm khi thiếu cấu hình, trước khi nạp các module của ứng dụng
    _require_env("EVENT_DB_URL")
    _require_env("TICKET_DB_URL")

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from app.models.event_vector import EventVector
from app.services.ai.rag_engine import rag_engine


def sync_events_to_vectordb():
    """Đồng bộ sự kiện sang bảng vector: đọc các sự kiện APPROVED chưa hết bán vé cùng suất diễn và loại vé, nhúng JSON mô tả thành vector rồi merge vào event_vectors.
    Lỗi được in ra và giao dịch bị rollback (không ném tiếp).
    """
    EVENT_DB_URL = _require_env("EVENT_DB_URL")
    TICKET_DB_URL = _require_env("TICKET_DB_URL")
    print("Connecting to Event DB and Ticket DB...")
    event_engine = create_engine(EVENT_DB_URL)
    EventSession = sessionmaker(bind=event_engine)
    event_session = EventSession()

    ticket_engine = create_engine(TICKET_DB_URL)
    TicketSession = sessionmaker(bind=ticket_engine)
    ticket_session = TicketSession()

    try:
        # lấy các sự kiện đã duyệt và chưa hết bán vé (sale_end_time rỗng hoặc ở tương lai)
        events_query = text("""
            SELECT id, title, description, location_name, city, dates_summary, min_price, banner_url, start_time, end_time, sale_start_time, sale_end_time
            FROM events
            WHERE event_approval_status = 'APPROVED'
            AND (sale_end_time IS NULL OR sale_end_time > NOW())
        """)
        events = event_session.execute(events_query).fetchall()
        print(f"Found {len(events)} upcoming/ongoing events.")

        for event in events:
            event_id = event.id
            title = event.title
            
            # lấy các suất diễn
            showtimes_query = text("SELECT id, name, start_time, end_time FROM event_showtimes WHERE event_id = :event_id")
            showtimes = event_session.execute(showtimes_query, {"event_id": event_id}).fetchall()
            
            # lấy các loại vé
            tickets_query = text("SELECT name, price, ticket_type_status, total_quantity FROM ticket_types WHERE event_id = :event_id")
            tickets = ticket_session.execute(tickets_query, {"event_id": event_id}).fetchall()

            # dựng nội dung chi tiết cho sự kiện
            content_dict = {
                "event_id": event_id,
                "title": title,
                "location": f"{event.location_name}, {event.city}",
                "banner_url": event.banner_url or "",
                "min_price": float(event.min_price) if event.min_price else 0,
                "start_time": event.start_time.strftime('%Y-%m-%d %H:%M') if event.start_time else "",
                "end_time": event.end_time.strftime('%Y-%m-%d %H:%M') if event.end_time else "",
                "description": event.description or "",
                "showtimes": [{"id": st.id, "name": st.name, "start": st.start_time.strftime('%Y-%m-%d %H:%M') if st.start_time else ""} for st in showtimes],
                "ticket_classes": [{"name": t.name, "price": float(t.price), "status": t.ticket_type_status, "total_quantity": t.total_quantity} for t in tickets]
            }

            content_json = json.dumps(content_dict, ensure_ascii=False)
            print(f"Processing event: {title}")
            
            vector = rag_engine.embeddings.embed_query(content_json)
            
            # lưu chuỗi JSON làm nội dung
            db_event = EventVector(id=event_id, event_id=event_id, content=content_json, embedding=vector)
            event_session.merge(db_event)
            
        event_session.commit()
        print("Successfully synced comprehensive event data to Vector DB!")

    except Exception as e:
        event_session.rollback()
        print(f"Error syncing events: {e}")
    finally:
        event_session.close()
        ticket_session.close()

if __name__ == "__main__":
    sync_events_to_vectordb()
