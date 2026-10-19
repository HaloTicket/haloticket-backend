import re
from langchain_community.utilities import SQLDatabase
from langchain_core.prompts import PromptTemplate
from app.services.ai.llm_factory import get_fast_llm
from app.core.config import settings

FORBIDDEN_SQL_KEYWORDS = {
    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", 
    "EXEC", "EXECUTE", "CREATE", "REPLACE", "GRANT", "REVOKE", 
    "LOCK", "ATTACH", "DETACH", "CALL", "VACUUM"
}

class SQLEngine:
    def __init__(self):
        # connect AI directly to the database (read-only query execution)
        self.db = SQLDatabase.from_uri(settings.database_url)
        self.llm = get_fast_llm()

    def sanitize_sql(self, sql_query: str) -> tuple[bool, str, str]:
        """
        Kiểm tra tính an toàn của câu truy vấn SQL trước khi thực thi.
        Chỉ cho phép SELECT và WITH (read-only), chặn các hành vi injection và thao tác dữ liệu.
        """
        clean_sql = sql_query.strip().strip(";").strip()
        
        # preventing multiple statements via semicolons
        if ";" in clean_sql:
            return False, "", "Không được phép thực thi nhiều câu truy vấn SQL cùng lúc."

        tokens = re.findall(r'\b[A-Za-z_]+\b', clean_sql.upper())
        if not tokens:
            return False, "", "Câu truy vấn SQL không hợp lệ hoặc rỗng."

        first_token = tokens[0]
        if first_token not in ("SELECT", "WITH"):
            return False, "", f"Chỉ cho phép truy vấn đọc dữ liệu (SELECT/WITH), không được bắt đầu bằng {first_token}."

        # check for prohibited keywords
        for token in tokens:
            if token in FORBIDDEN_SQL_KEYWORDS:
                return False, "", f"Phát hiện từ khóa nguy hiểm '{token}'. Chỉ cho phép truy vấn đọc dữ liệu."

        # adding LIMIT clause if not present
        if "LIMIT" not in tokens:
            clean_sql = f"{clean_sql} LIMIT 50"

        return True, clean_sql, ""

    def analyze_data(self, question: str):
        """Dịch câu hỏi của người dùng thành lệnh SQL an toàn và trả về kết quả kèm insight"""
        
        # 1. extract table schema
        table_info = self.db.get_table_info()
        
        # 2. build custom prompt using standard LangChain primitives
        prompt = PromptTemplate.from_template(
            "Bạn là chuyên gia PostgreSQL và Chuyên viên Phân tích Dữ liệu của HaloTicket.\n"
            "Cấu trúc Database (bao gồm các bảng từ Event Service, và bảng FDW từ Booking & Ticket Services):\n"
            "{table_info}\n\n"
            "Mối quan hệ & logic nghiệp vụ:\n"
            "- events.id = bookings.event_id\n"
            "- bookings (revenue/doanh thu): use SUM(bookings.total_amount) WHERE status in ('PAID', 'CONFIRMED')\n"
            "- tickets.event_id = events.id, ticket_types.event_id = events.id\n"
            "- Chỉ sinh câu lệnh SELECT hoặc WITH để đọc dữ liệu, TUYỆT ĐỐI KHÔNG sinh lệnh INSERT/UPDATE/DELETE/DROP.\n\n"
            "Trả VỀ DUY NHẤT MỘT câu truy vấn SQL (KHÔNG markdown, KHÔNG giải thích, CHỈ câu SQL) để trả lời câu hỏi sau:\n"
            "Câu hỏi: {question}"
        )
        
        try:
            # 3. chain prompt and LLM together and invoke
            chain = prompt | self.llm
            response = chain.invoke({"table_info": table_info, "question": question})
            
            # extract text from response content
            content_data = response.content
            if isinstance(content_data, list):
                text_content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content_data])
            else:
                text_content = str(content_data)
                
            # clean SQL code tags
            raw_sql = text_content.replace("```sql", "").replace("```sql\n", "").replace("```", "").strip()

            # 4. Safe SQL validation
            is_safe, sanitized_sql, error_msg = self.sanitize_sql(raw_sql)
            if not is_safe:
                return {
                    "question": question,
                    "generated_sql": raw_sql,
                    "error": error_msg,
                    "reply": f"Cảnh báo an toàn: {error_msg}"
                }
            
            # 5. execute SQL query to get raw result using sqlalchemy
            with self.db._engine.begin() as conn:
                from sqlalchemy import text
                result_proxy = conn.execute(text(sanitized_sql))
                columns = list(result_proxy.keys())
                rows = result_proxy.fetchall()
                data = [dict(zip(columns, row)) for row in rows]

            # 6. generate insights from data
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
                "sql_query": sanitized_sql, 
                "data": str(data[:10])
            })
            
            analysis_text = analysis_response.content
            if isinstance(analysis_text, list):
                analysis_text = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in analysis_text])
            
            return {
                "question": question,
                "generated_sql": sanitized_sql,
                "data": data,
                "reply": str(analysis_text).strip()
            }
        except Exception as e:
            return {
                "question": question,
                "error": str(e),
                "reply": f"Hệ thống gặp sự cố khi xử lý truy vấn: {str(e)}"
            }

sql_engine = SQLEngine()
