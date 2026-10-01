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
            "1. Viết một đoạn MÔ TẢ SỰ KIỆN (Description) thật hấp dẫn, chuyên nghiệp, chuẩn SEO, có sử dụng emoji phù hợp (KHÔNG sử dụng cú pháp in đậm ** của markdown).\n"
            "2. Viết một bộ ĐIỀU KHOẢN & QUY ĐỊNH (Terms and Conditions) chặt chẽ, đầy đủ, bảo vệ quyền lợi của Ban tổ chức và Khách hàng (KHÔNG sử dụng cú pháp in đậm ** của markdown).\n\n"
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
            return json.loads(content)
        except Exception as e:
            print(f"Lỗi khi parse JSON: {e}")
            return {
                "description": "Lỗi sinh nội dung. Vui lòng thử lại.",
                "terms_and_conditions": "Lỗi sinh điều khoản. Vui lòng thử lại."
            }

organizer_agent = OrganizerAgent()
