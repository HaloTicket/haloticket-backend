from app.services.ai.llm_factory import get_smart_llm
from app.services.ai.tools import find_adjacent_seats, search_events, request_user_confirmation, show_event_cards, show_seatmap, show_ticket_types, event_engine
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage, AIMessage
from app.services.ai.rag_engine import rag_engine
from sqlalchemy import text
import re
import json

class BookingAgent:
    def __init__(self):
        tools = [find_adjacent_seats, request_user_confirmation, show_event_cards, show_seatmap, show_ticket_types]
        self.llm_with_tools = get_smart_llm(tools=tools)
        self.system_prompt = SystemMessage(content="""Bạn là HaloTicket AI Assistant - Trợ lý đặt vé và tư vấn sự kiện thông minh của nền tảng HaloTicket.
Mục tiêu hàng đầu của bạn là mang lại trải nghiệm tư vấn tự nhiên, chu đáo, chính xác và DẪN DẮT khách hàng đặt vé qua một PIPELINE LIỀN MẠCH.

QUY TRÌNH HỘI THOẠI CHUẨN:
- Bước 0 (Chào hỏi & Khởi đầu): Khi khách hàng chỉ chào hỏi thông thường (VD: "xin chào", "hello", "chào bạn", "bạn là ai"), bạn BẮT BUỘC trả lời chào đón một cách niềm nở, thân thiện, giới thiệu bạn là trợ lý HaloTicket và hỏi khách hàng muốn tìm sự kiện, concert, nghệ sĩ hay trải nghiệm nào hôm nay. TUYỆT ĐỐI KHÔNG tự tiện gọi `show_event_cards` khi khách hàng chưa bày tỏ mong muốn tìm sự kiện.
- Bước 1 (Tìm kiếm & Khám phá): Khi khách hàng hỏi về sự kiện, tìm kiếm bằng ngôn ngữ tự nhiên (VD: "có show ca nhạc nào cuối tuần", "kịch thiếu nhi", "sự kiện thể thao chill", "tìm vé ca sĩ Hoàng Dũng", "gợi ý sự kiện hot"), bạn hãy viết lời dẫn dắt thân thiện và gọi tool `show_event_cards` với danh sách mã sự kiện (event_ids) tương ứng để hiển thị Thẻ Sự Kiện trực quan có ảnh và giá.
- Bước 2 (Chọn suất diễn, hạng vé & sơ đồ): Khi khách hàng chọn một sự kiện (hoặc cung cấp mã sự kiện / nhấn nút 'Chọn sự kiện'), bạn BẮT BUỘC gọi `show_ticket_types(event_id=...)`. 
  Công cụ này trả về chi tiết các Suất diễn (Showtimes), Trạng thái của từng suất diễn (Đang mở bán, Ngừng bán vé online, Hết vé, Sắp mở bán), các Hạng vé thuộc suất diễn đó, và sự kiện có sơ đồ ghế (seatmap) hay không.
  Trong câu trả lời của bạn:
  + Nêu rõ sự kiện có những suất diễn nào và trạng thái từng suất.
  + Nếu sự kiện CÓ sơ đồ ghế (has_seatmap = true): Hãy nhắc khách hàng có thể xem sơ đồ ghế hoặc chọn số lượng vé trực tiếp bên dưới. Phân biệt rõ khu vực có ghế ngồi (SEATED) và khu vực đứng (STANDING).
  + Nếu sự kiện KHÔNG có sơ đồ ghế: Hướng dẫn khách hàng chọn số lượng vé cho từng hạng vé trực tiếp từ bảng.
  + KHÔNG gọi lại `show_event_cards` khi đã ở bước này.
- Bước 3 (Sơ đồ ghế & Chọn vị trí): 
  + Nếu khách hàng muốn xem sơ đồ ghế (seatmap): BẮT BUỘC gọi `show_seatmap(event_id=...)`. Sơ đồ ghế tương tác (Interactive Seatmap Canvas) sẽ được nhúng hiển thị trực quan ngay dưới tin nhắn cho khách hàng. Bạn chỉ cần viết lời thoại dẫn dắt khách hàng xem sơ đồ bên dưới. TUYỆT ĐỐI KHÔNG tự sinh link ảnh banner hoặc link markdown bên ngoài vào văn bản.
  + Nếu khách hàng muốn tìm ghế liền kề (VD: "tìm cho tôi 2 ghế khu VIP"): BẮT BUỘC gọi `find_adjacent_seats(event_id=..., zone=..., num_seats=...)`.
- Bước 4 (Xác nhận đặt vé): Khi khách hàng đã chọn xong số lượng vé / ghế và đồng ý đặt vé, BẮT BUỘC gọi `request_user_confirmation` với:
  + action_type: "BOOK_TICKETS"
  + data: Một chuỗi JSON hợp lệ chứa: `{"event_id": "...", "event_title": "...", "showtime_id": "...", "showtime_name": "...", "tickets": [{"tier_name": "...", "quantity": 2, "price": 3000}], "seats": ["A1", "A2"], "total_price": 6000}`.

QUY TẮC CỐT LÕI (NGHIÊM CẤM VI PHẠM):
1. LUÔN LUÔN KÈM LỜI THOẠI: Dù có gọi Tool hay không, bạn LUÔN PHẢI CÓ câu trả lời bằng lời văn tự nhiên, thân thiện giải thích cho khách hàng biết bạn đang hỗ trợ điều gì.
2. TUYỆT ĐỐI KHÔNG BỊA ĐẶT DỮ LIỆU: Chỉ tư vấn các sự kiện, hạng vé và giá vé có thật trong hệ thống HaloTicket. NGHIÊM CẤM TỰ NGHĨ RA TÊN SỰ KIỆN HƯ CẤU (như 'Trăng Trại Hoa Phấn', 'Lối Trăng Ngoài Trời', 'Concert Ánh Trăng', 'Triển Diễn Ánh Trăng'...). Nếu hệ thống không có sự kiện nào khớp với yêu cầu của khách hàng, bạn BẮT BUỘC PHẢI NÓI RÕ là hiện tại HaloTicket chưa có sự kiện theo yêu cầu này, và gợi ý các sự kiện thực tế đang có trong hệ thống.
3. KHÔNG TỰ BỊA GIÁ TIỀN TRONG VĂN BẢN: Luôn dựa vào tool `show_ticket_types` để lấy giá chính xác theo thời gian thực.
4. KHÔNG NÓI RA MÃ SỰ KIỆN KỸ THUẬT (UUID) CHO KHÁCH HÀNG: Chỉ dùng mã này khi gọi Tool. Trong văn bản trò chuyện, hãy gọi bằng Tên sự kiện một cách thân thiện.
5. GIỌNG ĐIỆU: Thân thiện, tôn trọng, năng động, am hiểu nghệ thuật và luôn sẵn sàng hỗ trợ khách hàng nhanh chóng nhất.
""")

    def chat(self, user_message: str, history: list = None, db=None):
        if history is None: history = []
        user_message_clean = user_message.strip()
        user_message_lower = user_message_clean.lower()
        dynamic_prompt = self.system_prompt.content
        
        # detect pure greetings / small talk without explicit search intent
        greeting_words = {
            "xin chào", "chào", "chào bạn", "hello", "hi", "hey", "halo", "alo", 
            "chào ad", "ad ơi", "bạn ơi", "alo bạn", "có ai không", "bạn là ai", 
            "trợ lý là ai", "test", "hi bạn", "chào buổi sáng", "chào buổi tối"
        }
        is_pure_greeting = user_message_lower in greeting_words or (
            len(user_message_clean.split()) <= 3 and any(user_message_lower.startswith(g) for g in ["xin chào", "chào", "hello", "hi", "hey"])
        )
        
        # identify selection intent (choosing an event or asking for ticket tier)
        is_selection_intent = (
            "chọn sự kiện" in user_message_lower or 
            "đặt vé sự kiện" in user_message_lower or 
            "(mã:" in user_message_lower or 
            "mã:" in user_message_lower or
            "tôi muốn chọn hạng vé" in user_message_lower or
            "xem bảng giá" in user_message_lower or
            "xem hạng vé" in user_message_lower
        )

        # identify seatmap intent
        is_seatmap_intent = any(k in user_message_lower for k in [
            "sơ đồ ghế", "seatmap", "seat map", "sơ đồ chỗ ngồi", "xem chỗ ngồi", "vị trí ghế", "sơ đồ"
        ])
        
        injected_context = ""

        # handle seatmap intent directly without RAG embedding
        if is_seatmap_intent:
            target_event_id = None
            match = re.search(r'(?:mã|event_id|id)[\s:=]+(\d+)', user_message, re.IGNORECASE)
            if match:
                target_event_id = match.group(1)
            else:
                for h in reversed(history):
                    content = h.get("content", "")
                    m = re.search(r'(?:mã|event_id|id)[\s:=]+(\d+)', content, re.IGNORECASE)
                    if m:
                        target_event_id = m.group(1)
                        break
            if target_event_id:
                injected_context = f"\n\n[HỆ THỐNG]: Người dùng yêu cầu xem sơ đồ chỗ ngồi của sự kiện mã {target_event_id}. BẮT BUỘC gọi ngay công cụ `show_seatmap(event_id='{target_event_id}')` để hiển thị sơ đồ ghế cho người dùng."

        elif db and not is_pure_greeting and not is_selection_intent:
            sql_matched_events = []
            stop_words = {"sự", "kiện", "nào", "không", "cho", "tôi", "vài", "gần", "đây", "sắp", "diễn", "ra", "liên", "quan", "đến", "có", "tìm", "thấy", "thế", "nào", "với", "ạ", "nhé", "nha", "muốn", "xem", "hỏi", "về", "các", "những"}
            raw_tokens = [w for w in re.split(r'[,.!?\s]+', user_message_clean) if len(w) >= 2]
            clean_words = [w for w in raw_tokens if w.lower() not in stop_words]

            try:
                with event_engine.connect() as conn:
                    conditions = []
                    params = {}

                    if len(clean_words) >= 1:
                        kw_phrase = " ".join(clean_words)
                        conditions.append("e.title ILIKE :phrase")
                        conditions.append("e.description ILIKE :phrase")
                        params["phrase"] = f"%{kw_phrase}%"

                    for idx, kw in enumerate(clean_words):
                        pname = f"kw_{idx}"
                        conditions.append(f"e.title ILIKE :{pname}")
                        params[pname] = f"%{kw}%"

                    where_sql = " OR ".join(conditions) if conditions else "1=0"
                    
                    sql_q = text(f"""
                        SELECT e.id, e.title, e.description, e.city, e.location_name
                        FROM events e
                        WHERE ({where_sql})
                          AND (e.event_status IN ('LIVE', 'APPROVED') OR e.event_approval_status = 'APPROVED')
                          AND (e.end_time IS NULL OR e.end_time >= NOW())
                        ORDER BY e.created_at DESC
                        LIMIT 5
                    """)
                    if conditions:
                        for row in conn.execute(sql_q, params).fetchall():
                            sql_matched_events.append({
                                "id": str(row.id),
                                "title": row.title,
                                "location": f"{row.location_name or ''}, {row.city or ''}".strip(", "),
                                "description": row.description or ""
                            })
            except Exception as ex:
                print(f"[SQL DIRECT SEARCH WARNING] {ex}")

            # semantic vector search find events that match a user's natural language query
            relevant_results = []
            try:
                search_results = rag_engine.search_events(db, user_message, top_k=5)
                # filter events with high semantic similarity
                filtered_rag = [r for r in search_results if (1 - r.distance) >= 0.38]
                if filtered_rag:
                    with event_engine.connect() as conn:
                        eids = [r.EventVector.event_id for r in filtered_rag]
                        q = text("SELECT id FROM events WHERE id IN :eids AND (event_status IN ('LIVE', 'APPROVED') OR event_approval_status = 'APPROVED') AND (end_time IS NULL OR end_time >= NOW())")
                        active_ids = {str(row[0]) for row in conn.execute(q, {"eids": tuple(eids)}).fetchall()}
                        relevant_results = [r for r in filtered_rag if str(r.EventVector.event_id) in active_ids]
            except Exception as ex:
                print(f"[RAG SEARCH WARNING] {ex}")

            combined_event_ids = []
            combined_events_summary = []

            for se in sql_matched_events:
                eid = se["id"]
                if eid not in combined_event_ids:
                    combined_event_ids.append(eid)
                    combined_events_summary.append(f"- Mã sự kiện: {eid} | Tên: {se['title']} | Địa điểm: {se['location']}")

            for r in relevant_results:
                eid = str(r.EventVector.event_id)
                if eid not in combined_event_ids:
                    combined_event_ids.append(eid)
                    combined_events_summary.append(f"- Mã sự kiện: {eid} | Thông tin: {r.EventVector.content}")

            if combined_events_summary:
                events_text = "\n".join(combined_events_summary)
                injected_context = f"\n\n[DỮ LIỆU SỰ KIỆN PHÙ HỢP TỪ CƠ SỞ DỮ LIỆU HALOTICKET]:\n{events_text}\n-> Hãy giới thiệu ngắn gọn các sự kiện có thật trên đây cho khách hàng và gọi ngay `show_event_cards(event_ids={combined_event_ids})`. TUYỆT ĐỐI KHÔNG BỊA ĐẶT THÊM BẤT KỲ SỰ KIỆN NÀO KHÁC."
            else:
                # If neither SQL nor RAG found any match, suggest latest active events from DB
                try:
                    with event_engine.connect() as conn:
                        q = text("SELECT id, title, city FROM events WHERE (event_status IN ('LIVE', 'APPROVED') OR event_approval_status = 'APPROVED') AND (end_time IS NULL OR end_time >= NOW()) ORDER BY created_at DESC LIMIT 3")
                        latest_events = conn.execute(q).fetchall()
                        if latest_events:
                            latest_ids = [str(e.id) for e in latest_events]
                            latest_text = "\n".join([f"- Mã sự kiện: {e.id} (Tên: {e.title}, Địa điểm: {e.city})" for e in latest_events])
                            injected_context = f"\n\n[LƯU Ý QUAN TRỌNG TỪ HỆ THỐNG]:\nHệ thống cơ sở dữ liệu KHÔNG có bất kỳ sự kiện nào liên quan đến yêu cầu '{user_message_clean}'.\nTUYỆT ĐỐI KHÔNG TỰ BỊA ĐẶT RA TÊN SỰ KIỆN (như Trăng Trại Hoa Phấn, Lối Trăng Ngoài Trời, Concert Ánh Trăng, v.v.).\nBạn BẮT BUỘC phải trả lời rằng hiện tại HaloTicket chưa có sự kiện đúng chủ đề này, và giới thiệu các sự kiện thực tế đang có sau đây:\n{latest_text}\nVà gọi `show_event_cards(event_ids={latest_ids})`."
                except Exception as ex:
                    print(f"[RAG LATEST ERROR] {ex}")

        messages = [SystemMessage(content=dynamic_prompt)]
        
        # add conversation history
        for msg in history:
            if msg.get("role") == "user":
                messages.append(HumanMessage(content=msg.get("content", "")))
            elif msg.get("role") == "assistant":
                messages.append(AIMessage(content=msg.get("content", "")))
                
        # concatenate RAG context to the user's last message if available
        final_user_content = user_message + injected_context
        messages.append(HumanMessage(content=final_user_content))
        
        ai_response = self.llm_with_tools.invoke(messages)
        
        if hasattr(ai_response, 'tool_calls') and ai_response.tool_calls:
            results = []
            messages.append(ai_response)
            
            for tool_call in ai_response.tool_calls:
                tool_name = tool_call["name"]
                args = tool_call["args"]
                
                if tool_name == "find_adjacent_seats":
                    tool_result = find_adjacent_seats.invoke(args)
                    results.append({
                        "action": "HIGHLIGHT_SEATS",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    
                elif tool_name == "search_events":
                    keyword = args.get("keyword", user_message)
                    if db:
                        search_results = rag_engine.search_events(db, keyword, top_k=5)
                        events_text = "\n".join([f"- {r.EventVector.content}" for r in search_results])
                        if not events_text.strip():
                            events_text = "Hiện tại không tìm thấy sự kiện nào khớp với yêu cầu của bạn trên hệ thống HaloTicket."
                    else:
                        events_text = "Lỗi: Không kết nối được Database."
                        
                    messages.append(ToolMessage(content=events_text, tool_call_id=tool_call["id"]))

                elif tool_name == "request_user_confirmation":
                    tool_result = request_user_confirmation.invoke(args)
                    results.append({
                        "action": "USER_CONFIRMATION_REQUIRED",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
                    
                elif tool_name == "show_event_cards":
                    tool_result = show_event_cards.invoke(args)
                    results.append({
                        "action": "SHOW_EVENT_CARDS",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))

                elif tool_name == "show_seatmap":
                    tool_result = show_seatmap.invoke(args)
                    results.append({
                        "action": "SHOW_SEATMAP",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    try:
                        res_obj = json.loads(tool_result) if isinstance(tool_result, str) else tool_result
                        ev_name = res_obj.get("event_title", "")
                    except Exception:
                        ev_name = ""
                    msg_content = f"Đã hiển thị Sơ đồ ghế tương tác (Interactive Seatmap Canvas) cho sự kiện '{ev_name or args.get('event_id')}'. Giao diện sơ đồ khán phòng đã được nhúng trực tiếp ngay dưới tin nhắn để khách quan sát. Hãy hướng dẫn khách hàng quan sát vị trí sân khấu, các khu vực và ghế trên sơ đồ bên dưới, sau đó hỏi xem họ muốn chọn vé hoặc tìm vị trí ghế nào. TUYỆT ĐỐI KHÔNG tự tạo link ảnh banner hay link URL markdown trong tin nhắn."
                    messages.append(ToolMessage(content=msg_content, tool_call_id=tool_call["id"]))

                elif tool_name == "show_ticket_types":
                    tool_result = show_ticket_types.invoke(args)
                    results.append({
                        "action": "SHOW_TICKET_TYPES",
                        "extracted_args": args,
                        "database_data": tool_result
                    })
                    messages.append(ToolMessage(content=str(tool_result), tool_call_id=tool_call["id"]))
            
            # request natural conversational text explaining the tools result
            messages.append(HumanMessage(content="Dựa vào kết quả các công cụ trên, hãy viết câu trả lời ngắn gọn, thân thiện và dẫn dắt người dùng bước tiếp theo."))
            final_response = self.llm_with_tools.invoke(messages)
            
            raw_content = final_response.content if hasattr(final_response, "content") else ""
            if isinstance(raw_content, list):
                reply_text = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in raw_content])
            else:
                reply_text = str(raw_content)
            reply_text = reply_text.strip()
            
            # intelligent fallback if model returns empty text after tool execution
            if not reply_text:
                if any(r.get("action") == "SHOW_EVENT_CARDS" for r in results):
                    reply_text = "Dưới đây là một số sự kiện nổi bật và phù hợp trên HaloTicket dành cho bạn. Bạn có thể nhấn 'Chi tiết' để xem nội dung hoặc nhấn 'Chọn sự kiện' để mình hỗ trợ xem bảng giá và sơ đồ ghế nhé!"
                elif any(r.get("action") == "SHOW_TICKET_TYPES" for r in results):
                    reply_text = "Dưới đây là danh sách các suất diễn, trạng thái bán vé và các hạng vé chi tiết của sự kiện. Bạn có thể chọn suất diễn, số lượng vé mong muốn hoặc xem sơ đồ ghế trực tiếp bên dưới nhé!"
                elif any(r.get("action") == "SHOW_SEATMAP" for r in results):
                    reply_text = "Đây là sơ đồ chỗ ngồi của sự kiện. Bạn có thể xem vị trí từng khu vực hoặc cho mình biết khu vực bạn muốn ngồi nhé!"
                elif any(r.get("action") == "HIGHLIGHT_SEATS" for r in results):
                    reply_text = "Mình đã tìm được vị trí ghế liền kề phù hợp cho bạn trên sơ đồ. Bạn xem vị trí này đã ưng ý chưa để mình hướng dẫn xác nhận nhé!"
                elif any(r.get("action") == "USER_CONFIRMATION_REQUIRED" for r in results):
                    reply_text = "Vui lòng kiểm tra lại thông tin đơn đặt vé bên dưới và nhấn 'Xác nhận & Tới trang Đặt vé' để hoàn tất nhé!"
                else:
                    reply_text = "Thông tin đã sẵn sàng, bạn hãy xem chi tiết bên dưới nhé!"

            return {
                "reply": reply_text,
                "system_actions": results
            }
        else:
            content_data = ai_response.content
            text_content = "".join([part.get("text", "") if isinstance(part, dict) else str(part) for part in content_data]) if isinstance(content_data, list) else str(content_data)
            return {
                "reply": text_content.strip(),
                "system_actions": []
            }

booking_agent = BookingAgent()
