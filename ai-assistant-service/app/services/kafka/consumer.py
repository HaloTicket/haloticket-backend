import json
import threading
from confluent_kafka import Consumer, KafkaError
from app.core.config import settings
from app.db.session import SessionLocal
from app.models.event_vector import EventVector
from app.services.ai.rag_engine import rag_engine

def process_event_message(msg_value):
    """function to process event message"""
    data = json.loads(msg_value)
    event_id = data.get("eventId")
    # gather information for AI training
    content = f"{data.get('eventName', '')}. {data.get('description', '')}"

    if not event_id or not content.strip():
        return

    print(f"Got new event from Kafka: {event_id}. Processing...")
    db = SessionLocal()
    try:
        # get embeddings for event
        vector = rag_engine.embeddings.embed_query(content)
        
        # save into PostgreSQL
        db_event = EventVector(id=event_id, event_id=event_id, content=content, embedding=vector)
        db.merge(db_event) # merge to update if duplicate id, insert if new id
        db.commit()
        print(f"Saved event {event_id} into AI Database!")
    except Exception as e:
        print(f"Error when save event_vector: {e}")
    finally:
        db.close()

def start_kafka_consumer():
    """function to start kafka consumer"""
    conf = {
        'bootstrap.servers': settings.kafka_bootstrap_servers,
        'group.id': 'ai-assistant-group',
        'auto.offset.reset': 'earliest'
    }
    
    consumer = Consumer(conf)
    consumer.subscribe(['event-created-topic']) # topic that Java will send

    print("AI Service is listening to Kafka on topic: event-created-topic...")
    
    while True:
        msg = consumer.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            if msg.error().code() == KafkaError._PARTITION_EOF:
                continue
            else:
                print(f"Kafka error: {msg.error()}")
                break
        
        # receive valid message
        process_event_message(msg.value().decode('utf-8'))

    consumer.close()

def run_consumer_in_background():
    """Put the listening loop in a background thread so as not to block the Server API"""
    thread = threading.Thread(target=start_kafka_consumer, daemon=True)
    thread.start()
