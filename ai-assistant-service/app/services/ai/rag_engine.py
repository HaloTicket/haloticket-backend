"""Semantic event search. Nothing is built at import: the embeddings client is created on first use."""
import logging
import threading

from sqlalchemy import text

from app.services.ai.customer_sql import SEARCH_MIN_SCORE, VALID_EVENT_FILTER, vector_hit

log = logging.getLogger("ai.rag")


class EmbeddingUnavailable(Exception):
    """The query could not be turned into a vector (no key, provider error, timeout). Callers degrade to keyword search."""


# one statement: threshold and "still valid" are applied before LIMIT, so top-k is taken among usable events only.
# event_db: event_vectors(event_id, content, embedding), events(id, event_status, is_private, end_time).
VECTOR_SEARCH_SQL = text(f"""
    SELECT v.event_id AS event_id, v.content AS content,
           1 - (v.embedding <=> CAST(:vec AS vector)) AS score
    FROM event_vectors v
    JOIN events e ON e.id = v.event_id
    WHERE {VALID_EVENT_FILTER}
      AND 1 - (v.embedding <=> CAST(:vec AS vector)) >= :min_score
    ORDER BY v.embedding <=> CAST(:vec AS vector)
    LIMIT :top_k
""")


def _vector_literal(values) -> str:
    return "[" + ",".join(repr(float(v)) for v in values) + "]"


class RAGEngine:
    def __init__(self):
        self._embeddings = None
        self._lock = threading.Lock()

    @property
    def embeddings(self):
        """Created on the first request (keeps import free of network clients and of key checks)."""
        if self._embeddings is None:
            with self._lock:
                if self._embeddings is None:
                    from app.services.ai.llm_factory import get_embeddings
                    try:
                        self._embeddings = get_embeddings()
                    except Exception as failure:
                        raise EmbeddingUnavailable(type(failure).__name__) from failure
        return self._embeddings

    @embeddings.setter
    def embeddings(self, value):
        self._embeddings = value

    def embed(self, query: str):
        search_query = query if query and query.strip() != "" else "sự kiện"
        try:
            vector = self.embeddings.embed_query(search_query)
        except EmbeddingUnavailable:
            raise
        except Exception as failure:
            log.warning("Embedding failed: %s", type(failure).__name__)
            raise EmbeddingUnavailable(type(failure).__name__) from failure
        if not vector:
            raise EmbeddingUnavailable("empty vector")
        return vector

    def search_events(self, db, query: str, top_k: int = 3, min_score: float = SEARCH_MIN_SCORE):
        """Nearest valid events to the question, best first. `db` is anything with .execute(): a Connection of the
        read-only event engine, or a Session. Returns plain objects (r.EventVector.event_id, r.EventVector.content,
        r.distance = cosine distance). Raises EmbeddingUnavailable when the question cannot be embedded; database
        errors propagate to the caller. The read transaction is ended before returning so the caller can spend
        seconds on a model call without holding a pooled connection."""
        vector = self.embed(query)
        try:
            rows = db.execute(
                VECTOR_SEARCH_SQL,
                {"vec": _vector_literal(vector), "min_score": float(min_score), "top_k": int(top_k)},
            ).fetchall()
        finally:
            try:
                db.rollback()
            except Exception:
                pass
        return [vector_hit(r.event_id, r.content, r.score) for r in rows]


# a shared instance; building it creates no client and makes no call
rag_engine = RAGEngine()
