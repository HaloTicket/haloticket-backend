from sqlalchemy import true
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
            # 1. test model Chat
            response = self.llm.invoke([HumanMessage(content="Hello, who are you?")])
            print("AI answer:", response.content)

            # 2. test model Embedding 
            vector = self.embeddings.embed_query("This is a music event")
            print("Vector size:", len(vector)) # expectation is 768

            return True
        except Exception as e:
            print("Error connection with AI:", e)
            return False

    def search_events(self, db, query: str, top_k: int = 3):
        # 1. convert customer questions into vectors
        search_query = query if query and query.strip() != "" else "sự kiện"
        query_vector = self.embeddings.embed_query(search_query)

        # 2. search the database for events with the most similar vectors (Semantic Search)
        distance_col = EventVector.embedding.cosine_distance(query_vector).label("distance")
        results = db.query(EventVector, distance_col).order_by(
            distance_col
        ).limit(top_k).all()

        return results

# initialize a shared instance
rag_engine = RAGEngine()