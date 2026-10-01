from langchain_community.utilities import SQLDatabase
from langchain_core.prompts import PromptTemplate
# from app.services.ai.llm_factory import get_smart_llm
from app.services.ai.llm_factory import get_fast_llm
from app.core.config import settings

class SQLEngine:
    def __init__(self):
        # connect AI directly to the database (only read-only)
        # use event_db for now. for ticket analysis, point to the booking_db url
        self.db = SQLDatabase.from_uri(settings.database_url)
        # self.llm = get_smart_llm()
        self.llm = get_fast_llm()

    def analyze_data(self, question: str):
        """Dịch câu hỏi của người dùng thành lệnh SQL và trả về kết quả"""
        
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
            "- tickets.event_id = events.id, ticket_types.event_id = events.id\n\n"
            "Trả VỀ DUY NHẤT MỘT câu truy vấn SQL (KHÔNG markdown, KHÔNG giải thích, CHỈ câu SQL) để trả lời câu hỏi sau:\n"
            "Câu hỏi: {question}"
        )
        
        try:
            # 3. chain prompt and LLM together and invoke
            chain = prompt | self.llm
            response = chain.invoke({"table_info": table_info, "question": question})
            
            # extract text from response content (Gemini sometimes returns a list of blocks)
            content_data = response.content
            if isinstance(content_data, list):
                text_content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content_data])
            else:
                text_content = str(content_data)
                
            # remove SQL code tags if AI generates them
            sql_query = text_content.replace("```sql", "").replace("```sql\n", "").replace("```", "").strip()
            
            # 4. execute SQL query to get raw result using sqlalchemy
            with self.db._engine.begin() as conn:
                from sqlalchemy import text
                result_proxy = conn.execute(text(sql_query))
                columns = result_proxy.keys()
                rows = result_proxy.fetchall()
                data = [dict(zip(columns, row)) for row in rows]
            # 5. generate insights from data
            analysis_prompt = PromptTemplate.from_template(
                "Bạn là một chuyên gia phân tích dữ liệu (Data Analyst) của hệ thống HaloTicket.\n"
                "Người dùng đã hỏi: {question}\n"
                "Câu truy vấn SQL đã chạy: {sql_query}\n"
                "Kết quả dữ liệu trả về từ Database: {data}\n\n"
                "Nhiệm vụ của bạn:\n"
                "- Viết một đoạn tóm tắt phân tích dữ liệu (khoảng 2-3 câu) bằng tiếng Việt thật chuyên nghiệp, dễ hiểu.\n"
                "- Đưa ra một nhận xét, đánh giá hoặc phán đoán kinh doanh từ số liệu này (Ví dụ: sự kiện nào đang hot, xu hướng mua vé, đề xuất marketing...).\n"
                "CHỈ TRẢ VỀ NỘI DUNG PHÂN TÍCH, không lặp lại câu hỏi hay SQL."
            )
            analysis_chain = analysis_prompt | self.llm
            analysis_response = analysis_chain.invoke({"question": question, "sql_query": sql_query, "data": str(data[:10])})
            
            analysis_text = analysis_response.content
            if isinstance(analysis_text, list):
                analysis_text = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in analysis_text])
            
            return {
                "question": question,
                "generated_sql": sql_query,
                "data": data,
                "reply": str(analysis_text)
            }
        except Exception as e:
            return {"error": str(e)}

sql_engine = SQLEngine()
