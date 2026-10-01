from app.services.ai.llm_factory import get_smart_llm
from app.services.ai.tools import find_adjacent_seats, search_events, request_user_confirmation, show_event_cards, show_seatmap, show_ticket_types
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage, AIMessage
from app.services.ai.rag_engine import rag_engine

class BookingAgent:
    def __init__(self):
        tools = [find_adjacent_seats, request_user_confirmation, show_event_cards, show_seatmap, show_ticket_types]
        self.llm_with_tools = get_smart_llm(tools=tools)
        self.system_prompt = SystemMessage(content="""Bạn là HaloTicket AI Assistant, trợ lý ảo thông minh. Vai trò của bạn là DẪN DẮT khách hàng đặt vé qua một PIPELINE LIỀN MẠCH, thay vì chỉ "hỏi gì trả lời nấy".

LUẬT TỐI QUAN TRỌNG:
1. LUÔN CHỦ ĐỘNG DẪN DẮT THEO PIPELINE 4 BƯỚC:
   - Bước 1 (Tìm kiếm & Chọn sự kiện): BẮT BUỘC gọi `show_event_cards` để hiển thị nút bấm (Cards) cho sự kiện, sau đó hỏi: "Bạn muốn tìm hiểu thêm hoặc đặt vé cho sự kiện nào?"
   - Bước 2 (Chọn loại vé): BẮT BUỘC gọi tool `show_ticket_types` để lấy giá vé mới nhất. Sau đó hiển thị bảng giá và hỏi: "Bạn muốn đặt loại vé nào và số lượng bao nhiêu?". NẾU NGƯỜI DÙNG ĐÃ CHỌN SỰ KIỆN RỒI, TUYỆT ĐỐI KHÔNG GỌI LẠI `show_event_cards`.
   - Bước 3 (Chọn ghế & Seatmap): BẮT BUỘC gọi công cụ `show_seatmap` để hiển thị sơ đồ ghế. Sau đó hỏi: "Bạn muốn chọn vị trí/khu vực nào trên sơ đồ?"
   - Bước 4 (Xác nhận đặt vé): Sau khi khách hàng chốt, BẮT BUỘC gọi `request_user_confirmation` với action_type="BOOK_TICKETS" để hiển thị nút Thanh toán.
2. KHÔNG HIỂN THỊ MÃ SỰ KIỆN (event_id): Luôn dùng tên sự kiện.
3. TUYỆT ĐỐI KHÔNG BỊA DỮ LIỆU: Chỉ trả lời các sự kiện có trong [DỮ LIỆU TỪ HỆ THỐNG HALOTICKET].
4. TRÁNH BÁO GIÁ SAI TRONG TEXT: Dữ liệu RAG có thể chứa giá cũ. KHÔNG TỰ BÁO GIÁ trong văn bản hoặc bảng. Hãy hướng dẫn người dùng xem giá chính xác trên THẺ SỰ KIỆN (Cards) bên dưới hoặc dùng tool `show_ticket_types` để lấy bảng giá mới nhất.
5. LINH HOẠT THEO NGỮ CẢNH: Nếu người dùng thay đổi ý định và hỏi một câu hoàn toàn mới (VD: "Tìm cho tôi sự kiện khác", "Có concert nào gần đây"), BẠN PHẢI quay lại Bước 1 để trả lời câu hỏi đó, KHÔNG ĐƯỢC cứng nhắc tiếp tục Bước 2 của sự kiện cũ trong lịch sử chat.
""")

    def chat(self, user_message: str, history: list = None, db=None):
        if history is None: history = []
        user_message_lower = user_message.lower()
        dynamic_prompt = self.system_prompt.content
        
        # classic RAG Pre-fetch
        is_search_intent = any(kw in user_message_lower for kw in ["sự kiện", "danh sách", "liệt kê", "có gì", "hot", "event", "concert"])
        is_selection_intent = "chọn sự kiện" in user_message_lower or "đặt vé sự kiện" in user_message_lower or "(mã:" in user_message_lower
        
        if db and is_search_intent and not is_selection_intent:
            search_results = rag_engine.search_events(db, user_message, top_k=5)
            if search_results:
                events_text = "\n".join([f"- Mã sự kiện: {r.EventVector.event_id}\n  Mô tả: {r.EventVector.content}" for r in search_results])
                print(f"[RAG INFO] Found {len(search_results)} events matching query.", flush=True)
            else:
                events_text = "Hệ thống hiện tại chưa có sự kiện nào phù hợp."
                print("[RAG INFO] No events found.", flush=True)
                
            injected_context = f"\n\n--- BẮT ĐẦU DỮ LIỆU TỪ HỆ THỐNG HALOTICKET ---\nDưới đây là dữ liệu DUY NHẤT bạn được phép sử dụng. NẾU KHÔNG CÓ SỰ KIỆN NÀO MATCH, HÃY TRẢ LỜI 'HỆ THỐNG KHÔNG CÓ SỰ KIỆN NÀY'. BỎ QUA GIÁ TIỀN TRONG DỮ LIỆU NÀY NẾU CÓ, HÃY BẢO KHÁCH HÀNG XEM GIÁ TRÊN THẺ (CARDS).\n\n{events_text}\n--- KẾT THÚC DỮ LIỆU ---"
            print("[RAG INFO] Injected events into Context", flush=True)
        else:
            injected_context = ""

        messages = [SystemMessage(content=dynamic_prompt)]
        
        for msg in history:
            if msg.get("role") == "user":
                messages.append(HumanMessage(content=msg.get("content", "")))
            elif msg.get("role") == "assistant":
                messages.append(AIMessage(content=msg.get("content", "")))
                
        # Inject RAG context directly into the final user message!
        final_user_content = user_message + injected_context
        messages.append(HumanMessage(content=final_user_content))
        
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

                elif tool_call["name"] == "request_user_confirmation":
                    args = tool_call["args"]
                    tool_result = request_user_confirmation.invoke(args)
                    results.append({
                        "action": "USER_CONFIRMATION_REQUIRED",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    needs_final_response = True
                    
                elif tool_call["name"] == "show_event_cards":
                    args = tool_call["args"]
                    tool_result = show_event_cards.invoke(args)
                    results.append({
                        "action": "SHOW_EVENT_CARDS",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    needs_final_response = True

                elif tool_call["name"] == "show_seatmap":
                    args = tool_call["args"]
                    tool_result = show_seatmap.invoke(args)
                    results.append({
                        "action": "SHOW_SEATMAP",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    needs_final_response = True

                elif tool_call["name"] == "show_ticket_types":
                    args = tool_call["args"]
                    tool_result = show_ticket_types.invoke(args)
                    results.append({
                        "action": "SHOW_TICKET_TYPES",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
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
