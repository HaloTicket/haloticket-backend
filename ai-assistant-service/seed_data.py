from app.db.session import SessionLocal
from app.models.event_vector import EventVector
from app.services.ai.rag_engine import rag_engine
import uuid

def seed_database():
    db = SessionLocal()
    
    # 3 events of vastly different natures
    sample_events = [
        {
            "event_id": "EV-001", 
            "content": "Sự kiện âm nhạc EDM Ravolution cực cháy tại TP.HCM. Có DJ quốc tế, âm thanh bass đập tung nóc. Rất phù hợp cho giới trẻ, nhóm bạn thích quẩy và quậy hết mình."
        },
        {
            "event_id": "EV-002", 
            "content": "Buổi hòa nhạc giao hưởng mùa thu nhẹ nhàng tại Nhà hát lớn. Không gian sang trọng, yên tĩnh. Phù hợp cho các cặp đôi đi hẹn hò lãng mạn cuối tuần."
        },
        {
            "event_id": "EV-003", 
            "content": "Hội thảo chia sẻ kinh nghiệm Đầu tư Bất động sản và Chứng khoán 2026. Gặp gỡ các shark, phù hợp cho người đi làm, thích kinh doanh và làm giàu."
        }
    ]
    
    print("AI Reading and encoding events into Vectors...")
    for ev in sample_events:
        # call Gemini to encode text into 3072-dimensional Vectors
        vector = rag_engine.embeddings.embed_query(ev["content"])
        
        # save to PostgreSQL
        db_event = EventVector(
            id=str(uuid.uuid4()),
            event_id=ev["event_id"],
            content=ev["content"],
            embedding=vector
        )
        db.add(db_event)
    
    db.commit()
    db.close()
    print("Successfully inserted data into PostgreSQL!")

if __name__ == "__main__":
    seed_database()
