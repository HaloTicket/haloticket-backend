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
        """Translate customer questions into SQL queries and return results"""
        
        # 1. extract table schema
        table_info = self.db.get_table_info()
        
        # 2. build custom prompt using standard LangChain primitives
        prompt = PromptTemplate.from_template(
            "You are a PostgreSQL expert. Based on the following database structure:\n"
            "{table_info}\n\n"
            "Write ONLY ONE SQL query (NO markdown, NO explanation, JUST the SQL query) to answer the following question:\n"
            "Question: {question}"
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
            
            # 4. execute SQL query to get raw result
            db_result = self.db.run(sql_query)
            
            return {
                "question": question,
                "generated_sql": sql_query,
                "raw_result": db_result
            }
        except Exception as e:
            return {"error": str(e)}

sql_engine = SQLEngine()
