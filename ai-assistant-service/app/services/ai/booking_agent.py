from app.services.ai.llm_factory import get_smart_llm
from app.services.ai.tools import find_adjacent_seats, search_events
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from app.services.ai.rag_engine import rag_engine

class BookingAgent:
    def __init__(self):
        tools = [find_adjacent_seats, search_events]
        self.llm_with_tools = get_smart_llm(tools=tools)
        self.system_prompt = SystemMessage(content="""Bạn là HaloTicket AI Assistant, trợ lý ảo bán vé thông minh.
LUẬT TỐI QUAN TRỌNG:
1. KHI NGƯỜI DÙNG HỎI VỀ SỰ KIỆN (vd: "liệt kê danh sách sự kiện", "tất cả sự kiện", "có sự kiện gì", "tìm sự kiện..."), BẠN BẮT BUỘC PHẢI GỌI CÔNG CỤ (TOOL) `search_events`.
2. Nếu người dùng hỏi chung chung không có từ khóa cụ thể (vd: "tất cả sự kiện", "danh sách sự kiện hiện tại"), hãy gọi tool `search_events` với tham số `keyword=""`.
3. TUYỆT ĐỐI KHÔNG TỰ BỊA DỮ LIỆU. Bạn chỉ được cung cấp thông tin sự kiện NẾU NÓ ĐƯỢC TRẢ VỀ TỪ CÔNG CỤ `search_events`.
4. Khi người dùng muốn đặt ghế, hãy gọi công cụ `find_adjacent_seats`.
5. Trình bày danh sách sự kiện bằng Bảng Markdown rõ ràng. Trả lời bằng tiếng Việt thân thiện.
""")

    def chat(self, user_message: str, db=None):
        user_message_lower = user_message.lower()
        dynamic_prompt = self.system_prompt.content
        
        # classic RAG Pre-fetch
        if db and any(kw in user_message_lower for kw in ["sự kiện", "danh sách", "liệt kê", "có gì", "hot", "event", "concert"]):
            search_results = rag_engine.search_events(db, user_message, top_k=5)
            if search_results:
                events_text = "\n".join([f"- Mã sự kiện: {r.EventVector.event_id}\n  Mô tả: {r.EventVector.content}" for r in search_results])
            else:
                events_text = "Hệ thống hiện tại chưa có sự kiện nào."
                
            dynamic_prompt += f"\n\n[DỮ LIỆU TỪ HỆ THỐNG HALOTICKET]\nDưới đây là dữ liệu thật từ Database. BẠN PHẢI DÙNG DỮ LIỆU NÀY ĐỂ TRẢ LỜI NGƯỜI DÙNG BẰNG BẢNG MARKDOWN.\nLƯU Ý CỰC KỲ QUAN TRỌNG: Chỉ dùng đúng thông tin có trong dữ liệu này. Không tự bịa thêm các cột (như Ngày tháng, Giá vé) nếu dữ liệu không có. Không bịa ra sự kiện khác.\nDữ liệu:\n{events_text}"

        messages = [SystemMessage(content=dynamic_prompt), HumanMessage(content=user_message)]
        ai_response = self.llm_with_tools.invoke(messages)
        
        if hasattr(ai_response, 'tool_calls') and ai_response.tool_calls:
            results = []
            needs_final_response = False
            messages.append(ai_response) # add assistant's tool call to history
            
            for tool_call in ai_response.tool_calls:
                if tool_call["name"] == "find_adjacent_seats":
                    args = tool_call["args"]
                    tool_result = find_adjacent_seats.invoke(args)
                    results.append({
                        "action": "HIGHLIGHT_SEATS",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    needs_final_response = True
                    
                elif tool_call["name"] == "search_events":
                    args = tool_call["args"]
                    keyword = args.get("keyword", user_message)
                    
                    if db:
                        search_results = rag_engine.search_events(db, keyword, top_k=5)
                        events_text = "\n".join([f"- {r.EventVector.content}" for r in search_results])
                        if not events_text.strip():
                            events_text = "Hiện tại không tìm thấy sự kiện nào khớp với yêu cầu của bạn trên hệ thống HaloTicket."
                    else:
                        events_text = "Lỗi: Không kết nối được Database."
                        
                    messages.append(ToolMessage(content=events_text, tool_call_id=tool_call["id"]))
                    needs_final_response = True
            
            if needs_final_response:
                final_response = self.llm_with_tools.invoke(messages)
                return {
                    "reply": str(final_response.content),
                    "system_actions": results
                }
            
            return {
                "reply": "Tôi đã thực hiện thao tác cho bạn!",
                "system_actions": results
            }
        else:
            content_data = ai_response.content
            text_content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content_data]) if isinstance(content_data, list) else str(content_data)
            return {
                "reply": text_content,
                "system_actions": []
            }

booking_agent = BookingAgent()
