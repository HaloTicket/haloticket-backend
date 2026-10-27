from types import SimpleNamespace
from sqlalchemy import select
from app.services.ai.llm_factory import get_fast_llm, get_embeddings
from langchain_core.messages import HumanMessage
from app.models.event_vector import EventVector

class RAGEngine:
    def __init__(self):
        self.llm = get_fast_llm()
        self.embeddings = get_embeddings()

    def test_connection(self):
        """Hàm test nhanh xem API Key và Model có hoạt động không"""
        try:
            # test model Chat
            response = self.llm.invoke([HumanMessage(content="Hello, who are you?")])
            print("AI answer:", response.content)

            # test model Embedding 
            vector = self.embeddings.embed_query("This is a music event")
            print("Vector size:", len(vector)) # expectation is 768

            return True
        except Exception as e:
            print("Error connection with AI:", e)
            return False

    def search_events(self, db, query: str, top_k: int = 3):
        """Nearest events to the question. Returns plain objects (r.EventVector.event_id, r.EventVector.content,
        r.distance), not ORM instances, and ends the read transaction before returning, so the caller can spend
        seconds on an LLM call without holding a pooled database connection."""
        try:
            # convert customer questions into vectors
            search_query = query if query and query.strip() != "" else "sự kiện"
            query_vector = self.embeddings.embed_query(search_query)

            # search the database for events with the most similar vectors (Semantic Search)
            distance_col = EventVector.embedding.cosine_distance(query_vector).label("distance")
            rows = db.execute(
                select(EventVector.event_id, EventVector.content, distance_col).order_by(distance_col).limit(top_k)
            ).all()
            db.rollback()  # read only: hands the connection back to the pool
            return [
                SimpleNamespace(
                    EventVector=SimpleNamespace(event_id=row.event_id, content=row.content),
                    distance=float(row.distance),
                )
                for row in rows
            ]
        except Exception as e:
            print(f"[RAG ENGINE WARNING] Embed query failed or timed out: {e}")
            try:
                db.rollback()
            except Exception:
                pass
            return []

# initialize a shared instance
rag_engine = RAGEngine()