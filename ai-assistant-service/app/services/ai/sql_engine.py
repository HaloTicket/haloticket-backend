"""Động cơ phân tích dữ liệu bằng Text-to-SQL: LLM viết một câu SELECT, sql_guard kiểm tra, rồi chạy chỉ đọc trên tài khoản cơ sở dữ liệu riêng."""
import json
import logging
import time

from langchain_core.prompts import PromptTemplate
from fastapi import HTTPException
from sqlalchemy import inspect, text
from sqlalchemy.engine import make_url

from app.core.config import settings
from app.db.session import make_engine
from app.services.ai.llm_factory import get_fast_llm
from app.services.ai.sql_guard import GuardedSql, SqlGuardError, parse_allowed, validate_sql
from app.services.ai.scope import AnalysisScope

log = logging.getLogger("ai.sql_engine")


class AnalysisDbNotConfigured(HTTPException):
    """Chưa đặt AI_DB_USER / AI_DB_PASSWORD: phân tích dữ liệu không bao giờ quay về dùng tài khoản cơ sở dữ liệu chung. Trả HTTP 503 AI_DB_NOT_CONFIGURED."""

    def __init__(self):
        """Dựng lỗi HTTP 503 với mã AI_DB_NOT_CONFIGURED và thông báo tiếng Việt."""
        super().__init__(status_code=503, detail={
            "code": "AI_DB_NOT_CONFIGURED",
            "message": "Chức năng phân tích dữ liệu chưa được cấu hình tài khoản cơ sở dữ liệu chỉ đọc (AI_DB_USER / AI_DB_PASSWORD).",
        })


class SQLEngine:
    """Trả lời một câu hỏi về dữ liệu bằng cách để mô hình viết MỘT câu truy vấn, kiểm tra nó rồi chạy chỉ đọc.

    Phạm vi truy cập được quyết định hai lần: bởi tài khoản cơ sở dữ liệu (settings.ai_db_user, role chỉ đọc các view
    phân tích) và bởi sql_guard (danh sách relation cho phép, một SELECT, không hàm nguy hiểm, có LIMIT, không chú thích).
    Truy vấn chạy trong giao dịch chỉ đọc có giới hạn thời gian và kết quả bị giới hạn kích thước.
    """

    def __init__(self, llm=None):
        """Nhận LLM tùy chọn (mặc định get_fast_llm); engine kết nối được tạo lười ở lần dùng đầu."""
        self.llm = llm if llm is not None else get_fast_llm()
        self._engine = None
        self._schema_texts = {}  # tập relation cho phép -> (mô tả, thời điểm nạp)

    # cấu hình
    @property
    def allowed_relations(self) -> frozenset:
        """Tập relation (view) được phép truy vấn theo cấu hình ai_sql_allowed_relations."""
        return parse_allowed(settings.ai_sql_allowed_relations)

    @property
    def schema(self) -> str:
        """Tên schema phân tích theo cấu hình (rỗng nếu không đặt)."""
        return (settings.ai_analysis_schema or "").strip()

    def _analysis_url(self):
        """Dựng URL kết nối của tài khoản chỉ đọc (user và mật khẩu AI_DB_*) từ database_url. Ném AnalysisDbNotConfigured khi thiếu tài khoản."""
        self.require_account()
        return make_url(settings.database_url).set(username=settings.ai_db_user, password=settings.ai_db_password)

    @staticmethod
    def require_account() -> None:
        """Bảo đảm đã cấu hình tài khoản chỉ đọc; nếu thiếu thì ghi log lỗi và ném AnalysisDbNotConfigured."""
        if not settings.ai_db_user or not settings.ai_db_password:
            log.error("AI_DB_USER / AI_DB_PASSWORD are not set: data analysis is refused. "
                      "Create the read-only role (see postgres/ai-readonly) and set both.")
            raise AnalysisDbNotConfigured()

    @property
    def engine(self):
        """Engine của tài khoản chỉ đọc, tạo lười và đặt search_path theo schema phân tích nếu có."""
        if self._engine is None:
            options = {"connect_args": {"options": f"-c search_path={self.schema}"}} if self.schema else {}
            self._engine = make_engine(self._analysis_url(), **options)
        return self._engine

    # những gì mô hình được cho biết về dữ liệu
    def relations_for(self, scope: AnalysisScope) -> frozenset:
        """Các relation người gọi được truy vấn: các view đã cấu hình, và với ban tổ chức chỉ những view có thể giới hạn theo sự kiện."""
        spec = scope.spec()
        if spec is None:
            return self.allowed_relations
        return self.allowed_relations & (frozenset(spec.columns) | spec.public)

    def schema_description(self, relations=None) -> str:
        """Mô tả các view được phép (cột, kiểu, chú thích ghi trên đó) để đưa vào prompt. Cache 10 phút theo tập relation."""
        relations = frozenset(relations) if relations is not None else self.allowed_relations
        cached = self._schema_texts.get(relations)
        if cached and time.time() - cached[1] < 600:
            return cached[0]
        inspector = inspect(self.engine)
        schema = self.schema or None
        lines = []
        for relation in sorted(relations):
            try:
                columns = inspector.get_columns(relation, schema=schema)
            except Exception:
                continue
            try:
                comment = (inspector.get_table_comment(relation, schema=schema) or {}).get("text") or ""
            except Exception:
                comment = ""
            lines.append(f"- {relation}" + (f": {comment}" if comment else ""))
            for column in columns:
                note = f" -- {column['comment']}" if column.get("comment") else ""
                lines.append(f"    {column['name']} {column['type']}{note}")
        described = "\n".join(lines)
        self._schema_texts[relations] = (described, time.time())
        return described

    # chạy một câu truy vấn đã được kiểm tra

    def run_readonly(self, guarded: GuardedSql, event_scope: str = "*") -> list:
        """Chạy câu truy vấn đã qua sql_guard trong giao dịch chỉ đọc, có statement_timeout, và cắt kết quả theo ai_sql_max_result_chars.
        event_scope được đặt vào app.allowed_event_ids cho các view lọc theo nó ("*" = mọi sự kiện). Không bao giờ commit."""
        # dấu hai chấm được escape để SQLAlchemy không hiểu "'a:b'" là tham số bind
        statement = text(guarded.sql.replace(":", "\\:"))
        max_chars = settings.ai_sql_max_result_chars
        data, size = [], 0
        with self.engine.connect() as conn:
            try:
                conn.execute(text("SET TRANSACTION READ ONLY"))
                conn.execute(text(f"SET LOCAL statement_timeout = {int(settings.ai_sql_timeout_ms)}"))
                conn.execute(text("SELECT set_config('app.allowed_event_ids', :ids, true)"), {"ids": event_scope})
                result = conn.execute(statement)
                columns = list(result.keys())
                for row in result.fetchmany(guarded.limit):
                    record = dict(zip(columns, row))
                    size += len(json.dumps(record, default=str, ensure_ascii=False))
                    if size > max_chars:
                        log.warning("Analysis result cut at %d rows: more than %d characters", len(data), max_chars)
                        break
                    data.append(record)
            finally:
                conn.rollback()  # không bao giờ commit
        return data

    # câu hỏi của người dùng
    def analyze_data(self, question: str, scope: AnalysisScope = None):
        """Dịch câu hỏi của người dùng thành một câu SELECT an toàn, chạy chỉ-đọc và trả về kết quả kèm insight.
        scope: các sự kiện người gọi được xem (None hoặc phạm vi admin = tất cả). Lỗi được trả thành dict có khóa error và reply, không ném ra ngoài (trừ HTTP 503 khi thiếu tài khoản)."""
        self.require_account()  # ném 503 AI_DB_NOT_CONFIGURED trước mọi việc khác
        scope = scope or AnalysisScope.admin()
        relations = self.relations_for(scope)
        if not relations:
            return {
                "question": question,
                "error": "NO_ALLOWED_RELATIONS",
                "reply": "Tính năng phân tích dữ liệu chưa được cấu hình."
            }
        try:
            table_info = self.schema_description(relations)
        except Exception as failure:
            log.error("Could not read the analysis schema: %s", type(failure).__name__)
            return {"question": question, "error": "SCHEMA_UNAVAILABLE",
                    "reply": "Hệ thống chưa đọc được cấu trúc dữ liệu phân tích. Vui lòng thử lại sau."}

        prompt = PromptTemplate.from_template(
            "Bạn là chuyên gia PostgreSQL và Chuyên viên Phân tích Dữ liệu của HaloTicket.\n"
            "Bạn chỉ được truy vấn các view sau (đã loại bỏ dữ liệu cá nhân), KHÔNG dùng bất kỳ tên bảng nào khác:\n"
            "{table_info}\n\n"
            "{scope_note}"
            "Quy tắc:\n"
            "- Chỉ sinh MỘT câu lệnh SELECT (hoặc WITH ... SELECT), TUYỆT ĐỐI KHÔNG sinh INSERT/UPDATE/DELETE/DROP, "
            "không dùng chú thích SQL, không dùng dấu chấm phẩy ở giữa câu.\n"
            "- Luôn kết thúc bằng LIMIT (tối đa {max_rows}).\n"
            "- Nếu câu hỏi cần dữ liệu không có trong các view trên (ví dụ thông tin cá nhân của khách), "
            "hãy sinh: SELECT 'Không có dữ liệu này' AS thong_bao\n\n"
            "Trả VỀ DUY NHẤT MỘT câu truy vấn SQL (KHÔNG markdown, KHÔNG giải thích, CHỈ câu SQL) để trả lời câu hỏi sau:\n"
            "Câu hỏi: {question}"
        )

        try:
            chain = prompt | self.llm
            scope_note = "" if scope.is_admin else "Dữ liệu đã được giới hạn trong các sự kiện của người hỏi; chỉ trả lời về các sự kiện đó.\n"
            response = chain.invoke({"table_info": table_info, "question": question, "max_rows": settings.ai_sql_max_rows,
                                     "scope_note": scope_note})

            content_data = response.content
            if isinstance(content_data, list):
                text_content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content_data])
            else:
                text_content = str(content_data)
            raw_sql = text_content.replace("```sql", "").replace("```", "").strip()

            try:
                guarded = validate_sql(raw_sql, self.allowed_relations, schema=self.schema or None,
                                       max_rows=settings.ai_sql_max_rows, scope=scope.spec())
            except SqlGuardError as refusal:
                return {
                    "question": question,
                    "generated_sql": raw_sql,
                    "error": refusal.message,
                    "reply": f"Cảnh báo an toàn: {refusal.message}"
                }

            data = self.run_readonly(guarded, scope.session_value)

            analysis_prompt = PromptTemplate.from_template(
                "Bạn là một chuyên gia phân tích dữ liệu (Data Analyst) của hệ thống HaloTicket.\n"
                "Người dùng đã hỏi: {question}\n"
                "Câu truy vấn SQL đã chạy: {sql_query}\n"
                "Kết quả dữ liệu trả về từ Database: {data}\n\n"
                "Nhiệm vụ của bạn:\n"
                "- Viết một đoạn tóm tắt phân tích dữ liệu (khoảng 2-3 câu) bằng tiếng Việt thật chuyên nghiệp, dễ hiểu.\n"
                "- Đưa ra một nhận xét, đánh giá hoặc phán đoán kinh doanh từ số liệu này (Ví dụ: sự kiện nào đang hot, xu hướng mua vé, đề xuất marketing...).\n"
                "CHỈ TRẢ VỀ NỘI DUNG PHÂN TÍCH, không lặp lại câu hỏi hay SQL thô."
            )
            analysis_chain = analysis_prompt | self.llm
            analysis_response = analysis_chain.invoke({
                "question": question,
                "sql_query": guarded.sql,
                "data": str(data[:10])
            })

            analysis_text = analysis_response.content
            if isinstance(analysis_text, list):
                analysis_text = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in analysis_text])

            return {
                "question": question,
                "generated_sql": guarded.sql,
                "data": data,
                "reply": str(analysis_text).strip()
            }
        except Exception as failure:
            # chi tiết (thông báo driver, tên bảng) chỉ nằm trong log, không đưa vào phản hồi
            log.error("Data analysis failed: %s", type(failure).__name__)
            return {
                "question": question,
                "error": "ANALYSIS_FAILED",
                "reply": "Hệ thống gặp sự cố khi xử lý truy vấn. Vui lòng thử lại sau."
            }

sql_engine = SQLEngine()
