import os
import sys
import json
from datetime import datetime
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from app.models.event_vector import EventVector
from app.services.ai.rag_engine import rag_engine

EVENT_DB_URL = os.getenv("EVENT_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/event_db")
TICKET_DB_URL = os.getenv("TICKET_DB_URL", "postgresql+psycopg2://haloticket:secretpassword@postgres:5432/ticket_db")

def sync_events_to_vectordb():
    print("Connecting to Event DB and Ticket DB...")
    event_engine = create_engine(EVENT_DB_URL)
    EventSession = sessionmaker(bind=event_engine)
    event_session = EventSession()

    ticket_engine = create_engine(TICKET_DB_URL)
    TicketSession = sessionmaker(bind=ticket_engine)
    ticket_session = TicketSession()

    try:
        # fetch events that are approved and not ended (or sale_end_time is null or in the future)
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
            
            # Fetch showtimes
            showtimes_query = text("SELECT id, name, start_time, end_time FROM event_showtimes WHERE event_id = :event_id")
            showtimes = event_session.execute(showtimes_query, {"event_id": event_id}).fetchall()
            
            # fetch ticket types
            tickets_query = text("SELECT name, price, ticket_type_status, total_quantity FROM ticket_types WHERE event_id = :event_id")
            tickets = ticket_session.execute(tickets_query, {"event_id": event_id}).fetchall()

            # build detailed rich text
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
            
            # store JSON string as content!
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
