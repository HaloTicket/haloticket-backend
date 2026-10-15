from app.services.ai.llm_factory import get_smart_llm, get_fast_llm
from langchain_core.prompts import PromptTemplate
from langchain_core.messages import HumanMessage, SystemMessage
import json

class OrganizerAgent:
    def __init__(self):
        self.fast_llm = get_fast_llm()
        self.smart_llm = get_smart_llm()

    def generate_event_content(self, title: str, bullet_points: str) -> dict:
        """
        Sinh nội dung mô tả sự kiện và điều khoản dựa trên bullet points.
        """
        prompt = PromptTemplate.from_template(
            "Bạn là một chuyên gia Content Marketing & Pháp chế cho sự kiện của hệ thống HaloTicket.\n"
            "Người dùng (Ban tổ chức) đang tạo một sự kiện mới có tên: '{title}'.\n"
            "Dưới đây là các ý chính (bullet points) họ cung cấp:\n"
            "{bullet_points}\n\n"
            "Nhiệm vụ của bạn:\n"
            "1. Viết một đoạn MÔ TẢ SỰ KIỆN (Description) thật hấp dẫn, chuyên nghiệp, chuẩn SEO, có sử dụng emoji phù hợp.\n"
            "   QUY TẮC BẮT BUỘC: TUYỆT ĐỐI KHÔNG dùng cú pháp markdown như '#', '##', '###', in đậm '**', '*', '***' hay '__'. Hãy sử dụng văn bản thuần túy với các tiêu đề viết hoa và xuống dòng tự nhiên, hoặc gạch đầu dòng '-' đơn giản.\n"
            "2. Viết một bộ ĐIỀU KHOẢN & QUY ĐỊNH (Terms and Conditions) chặt chẽ, đầy đủ, bảo vệ quyền lợi của Ban tổ chức và Khách hàng.\n"
            "   QUY TẮC BẮT BUỘC: TUYỆT ĐỐI KHÔNG dùng ký tự markdown '#', '##', '###', '**', '***'. Đánh số thứ tự 1., 2., 3. rõ ràng.\n\n"
            "Trả về kết quả DƯỚI DẠNG CHUỖI JSON HỢP LỆ (KHÔNG bọc trong markdown block ```json):\n"
            '{{"description": "...", "terms_and_conditions": "..."}}'
        )
        
        chain = prompt | self.smart_llm
        response = chain.invoke({"title": title, "bullet_points": bullet_points})
        
        try:
            content = response.content
            if isinstance(content, list):
                content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content])
            
            # clean markdown code blocks if any
            content = content.strip().removeprefix("```json").removesuffix("```").strip()
            data = json.loads(content)
            
            def clean_markdown(text: str) -> str:
                if not text:
                    return ""
                import re
                # Strip markdown headers (##, ###, etc.)
                text = re.sub(r'#{1,6}\s*', '', text)
                # Strip bold/italic markdown (**text**, *text*, ***text***)
                text = re.sub(r'\*{1,3}([^*]+)\*{1,3}', r'\1', text)
                text = re.sub(r'_{1,3}([^_]+)_{1,3}', r'\1', text)
                return text.strip()

            return {
                "description": clean_markdown(data.get("description", "")),
                "terms_and_conditions": clean_markdown(data.get("terms_and_conditions", ""))
            }
        except Exception as e:
            print(f"Lỗi khi parse JSON: {e}")
            return {
                "description": "Lỗi sinh nội dung. Vui lòng thử lại.",
                "terms_and_conditions": "Lỗi sinh điều khoản. Vui lòng thử lại."
            }

organizer_agent = OrganizerAgent()
