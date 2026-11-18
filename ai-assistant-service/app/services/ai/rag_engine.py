"""Tìm kiếm sự kiện theo ngữ nghĩa. Không dựng gì khi import: client embedding chỉ được tạo ở lần dùng đầu tiên."""
import logging
import threading

from sqlalchemy import text

from app.services.ai.customer_sql import SEARCH_MIN_SCORE, VALID_EVENT_FILTER, vector_hit

log = logging.getLogger("ai.rag")


class EmbeddingUnavailable(Exception):
    """Không chuyển được câu hỏi thành vector (thiếu key, lỗi nhà cung cấp, quá thời gian). Nơi gọi hạ cấp sang tìm theo từ khóa."""


# một câu lệnh: ngưỡng điểm và điều kiện "còn hiệu lực" được áp trước LIMIT, nên top-k chỉ lấy trong các sự kiện dùng được.
# event_db: bảng event_vectors(event_id, content, embedding), events(id, event_status, is_private, end_time).
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
    """Đổi danh sách số thành chuỗi vector của pgvector, dạng [v1,v2,...]."""
    return "[" + ",".join(repr(float(v)) for v in values) + "]"


class RAGEngine:
    """Động cơ tìm kiếm ngữ nghĩa: tạo embedding cho câu hỏi và truy vấn vector gần nhất trong event_db."""
    def __init__(self):
        """Khởi tạo với embedding chưa tạo (tạo lười) và khóa chống tạo trùng giữa các luồng."""
        self._embeddings = None
        self._lock = threading.Lock()

    @property
    def embeddings(self):
        """Client embedding, tạo ở yêu cầu đầu tiên (để import không tạo client mạng hay kiểm tra key). Ném EmbeddingUnavailable nếu không tạo được."""
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
        """Gán client embedding (dùng để thay thế khi kiểm thử)."""
        self._embeddings = value

    def embed(self, query: str):
        """Chuyển câu hỏi thành vector (câu rỗng dùng từ "sự kiện"). Ném EmbeddingUnavailable khi lỗi hoặc vector rỗng."""
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
        """Tìm các sự kiện hợp lệ gần câu hỏi nhất, tốt nhất xếp trước. `db` là bất kỳ đối tượng có .execute():
        Connection của engine event chỉ đọc hoặc một Session. Trả về các đối tượng đơn giản (r.EventVector.event_id,
        r.EventVector.content, r.distance = khoảng cách cosine). Ném EmbeddingUnavailable khi không embed được câu hỏi;
        lỗi cơ sở dữ liệu được ném tiếp cho nơi gọi. Giao dịch đọc được kết thúc trước khi trả về để nơi gọi có thể
        mất vài giây cho lời gọi mô hình mà không giữ kết nối trong pool."""
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


# một instance dùng chung; dựng nó không tạo client và không gọi mạng
rag_engine = RAGEngine()
