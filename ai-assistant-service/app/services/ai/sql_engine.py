import json
import logging
import time

from langchain_core.prompts import PromptTemplate
from sqlalchemy import inspect, text
from sqlalchemy.engine import make_url

from app.core.config import settings
from app.db.session import make_engine
from app.services.ai.llm_factory import get_fast_llm
from app.services.ai.sql_guard import GuardedSql, SqlGuardError, parse_allowed, validate_sql
from app.services.ai.scope import AnalysisScope

log = logging.getLogger("ai.sql_engine")


class SQLEngine:
    """Answers a data question by letting the model write ONE query, checking it, and running it read-only.

    What can be reached is decided twice: by the database account (settings.ai_db_user, a role that can only read the
    analysis views) and by sql_guard (allow-list of relations, one SELECT, no dangerous functions, LIMIT, no comments).
    The query runs in a read-only transaction with a statement timeout and its result is capped.
    """

    def __init__(self):
        self.llm = get_fast_llm()
        self._engine = None
        self._schema_texts = {}  # allowed relations -> (text, loaded at)

    # configuration 
    @property
    def allowed_relations(self) -> frozenset:
        return parse_allowed(settings.ai_sql_allowed_relations)

    @property
    def schema(self) -> str:
        return (settings.ai_analysis_schema or "").strip()

    def _analysis_url(self):
        url = make_url(settings.database_url)
        if settings.ai_db_user:
            return url.set(username=settings.ai_db_user, password=settings.ai_db_password or None)
        log.warning("AI_DB_USER is not set: data analysis connects with the general database account. "
                    "Create the read-only role (see postgres/ai-readonly) and set AI_DB_USER / AI_DB_PASSWORD.")
        return url

    @property
    def engine(self):
        if self._engine is None:
            options = {"connect_args": {"options": f"-c search_path={self.schema}"}} if self.schema else {}
            self._engine = make_engine(self._analysis_url(), **options)
        return self._engine

    # what the model is told about the data 
    def relations_for(self, scope: AnalysisScope) -> frozenset:
        """What this caller may query: the configured views, and for an organizer only those that can be limited to events."""
        spec = scope.spec()
        if spec is None:
            return self.allowed_relations
        return self.allowed_relations & (frozenset(spec.columns) | spec.public)

    def schema_description(self, relations=None) -> str:
        """Only the allowed views, with the comments written on them. Cached for ten minutes."""
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

    # running a checked query 

    def run_readonly(self, guarded: GuardedSql, event_scope: str = "*") -> list:
        """event_scope goes into app.allowed_event_ids for the views that filter by it ("*" = all events)."""
        # a colon is escaped so SQLAlchemy does not read "'a:b'" as a bind parameter
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
                conn.rollback()  # nothing is ever committed
        return data

    # the question
    def analyze_data(self, question: str, scope: AnalysisScope = None):
        """Dịch câu hỏi của người dùng thành một câu SELECT an toàn, chạy chỉ-đọc và trả về kết quả kèm insight.
        scope: which events the caller may see (None or an admin scope = all)."""
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
            # the details (driver messages, table names) stay in the log, not in the response
            log.error("Data analysis failed: %s", type(failure).__name__)
            return {
                "question": question,
                "error": "ANALYSIS_FAILED",
                "reply": "Hệ thống gặp sự cố khi xử lý truy vấn. Vui lòng thử lại sau."
            }

sql_engine = SQLEngine()
