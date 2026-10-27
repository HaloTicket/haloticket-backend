import os
import re
import time
import json
import hashlib
from datetime import datetime
from typing import Optional, List, Dict, Any
from sqlalchemy import create_engine, text
from langchain_core.prompts import PromptTemplate
from langchain_core.messages import HumanMessage, SystemMessage
from app.services.ai.llm_factory import get_smart_llm, get_fast_llm
from app.services.ai.sql_engine import sql_engine
from app.core.config import settings
from app.db.session import make_engine

# database URLs
EVENT_DB_URL = os.getenv("EVENT_DB_URL", settings.database_url or "postgresql+psycopg2://haloticket:secretpassword@localhost:5433/event_db")
TICKET_DB_URL = os.getenv("TICKET_DB_URL", EVENT_DB_URL.replace("/event_db", "/ticket_db"))
BOOKING_DB_URL = os.getenv("BOOKING_DB_URL", EVENT_DB_URL.replace("/event_db", "/booking_db"))

try:
    event_engine = make_engine(EVENT_DB_URL)
except Exception:
    event_engine = None

try:
    ticket_engine = make_engine(TICKET_DB_URL)
except Exception:
    ticket_engine = None

try:
    booking_engine = make_engine(BOOKING_DB_URL)
except Exception:
    booking_engine = None


class AgentCache:
    """In-memory TTL Cache để giảm độ trễ và chi phí token cho các truy vấn lặp lại"""
    def __init__(self, ttl_seconds: int = 300):
        self.ttl = ttl_seconds
        self.cache: Dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Optional[Any]:
        item = self.cache.get(key)
        if not item:
            return None
        timestamp, data = item
        if time.time() - timestamp > self.ttl:
            del self.cache[key]
            return None
        return data

    def set(self, key: str, value: Any):
        # limit cache size to prevent RAM overflow
        if len(self.cache) > 500:
            oldest_key = min(self.cache.keys(), key=lambda k: self.cache[k][0])
            del self.cache[oldest_key]
        self.cache[key] = (time.time(), value)

    def clear(self):
        self.cache.clear()


class OrganizerAgent:
    """
    AI Data Assistant chuyên trách dành cho Ban Tổ Chức (Organizer) của HaloTicket.
    Thiết kế theo chuẩn AI Agent với Agent Loop & Multi-step Pipeline:
    1. INTENT & PLANNING: Phân tích mục tiêu câu hỏi & lập kế hoạch hành động.
    2. TOOL EXECUTION: Kích hoạt công cụ truy xuất cơ sở dữ liệu thực tế (Analytics, Showtimes, Voucher, Announcement, SQL Engine).
    3. SYNTHESIS & REASONING: Mô hình ngôn ngữ suy luận sâu, tổng hợp insight và đề xuất giải pháp kinh doanh.
    4. RESPONSE CACHING: Bộ nhớ đệm thông minh tối ưu tốc độ phản hồi sub-second.
    """

    def __init__(self):
        self.fast_llm = get_fast_llm()
        self.smart_llm = get_smart_llm()
        self.cache = AgentCache(ttl_seconds=300)

    # legacy method: generate event content
    def generate_event_content(self, title: str, bullet_points: str) -> dict:
        """Sinh nội dung mô tả sự kiện và điều khoản dựa trên bullet points."""
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
                # strip markdown headers (##, ###, etc.)
                text = re.sub(r'#{1,6}\s*', '', text)
                # strip bold/italic markdown (**text**, *text*, ***text***)
                text = re.sub(r'\*{1,3}([^*]+)\*{1,3}', r'\1', text)
                text = re.sub(r'_{1,3}([^_]+)_{1,3}', r'\1', text)
                return text.strip()

            return {
                "description": clean_markdown(data.get("description", "")),
                "terms_and_conditions": clean_markdown(data.get("terms_and_conditions", ""))
            }
        except Exception as e:
            return {
                "description": "Lỗi sinh nội dung. Vui lòng thử lại.",
                "terms_and_conditions": "Lỗi sinh điều khoản. Vui lòng thử lại."
            }

    # tool 1: query event analytics
    def _tool_event_analytics(self, event_id: Optional[str], event_title: Optional[str]) -> Dict[str, Any]:
        """Truy vấn dữ liệu doanh thu, số vé đã bán và đơn hàng thực tế"""
        engine = event_engine or sql_engine.db._engine
        result = {
            "event_id": event_id,
            "event_title": event_title or "Sự kiện",
            "total_revenue": 0,
            "paid_orders": 0,
            "tickets_sold": 0,
            "total_capacity": 0,
            "occupancy_rate": 0.0,
            "ticket_types": []
        }

        try:
            with engine.connect() as conn:
                # 1. event info
                if event_id:
                    ev_row = conn.execute(
                        text("SELECT id, title, event_status, min_price, dates_summary FROM events WHERE id = :eid"),
                        {"eid": event_id}
                    ).fetchone()
                    if ev_row:
                        result["event_title"] = ev_row.title or result["event_title"]
                        result["event_status"] = ev_row.event_status

                # 2. total ticket and ticket types
                tt_engine = ticket_engine or engine
                with tt_engine.connect() as tconn:
                    if event_id:
                        tt_rows = tconn.execute(
                            text("SELECT id, name, price, total_quantity, ticket_type_status FROM ticket_types WHERE event_id = :eid"),
                            {"eid": event_id}
                        ).fetchall()
                    else:
                        tt_rows = tconn.execute(
                            text("SELECT id, name, price, total_quantity, ticket_type_status FROM ticket_types LIMIT 10")
                        ).fetchall()

                    for r in tt_rows:
                        result["ticket_types"].append({
                            "name": r.name,
                            "price": float(r.price or 0),
                            "capacity": int(r.total_quantity or 0),
                            "status": r.ticket_type_status
                        })
                        result["total_capacity"] += int(r.total_quantity or 0)

                # 3. revenue and order from bookings / tickets
                bk_engine = booking_engine or engine
                with bk_engine.connect() as bconn:
                    if event_id:
                        bk_row = bconn.execute(
                            text("""
                                SELECT 
                                    COALESCE(SUM(total_amount), 0) as total_rev,
                                    COUNT(id) as total_bookings
                                FROM bookings 
                                WHERE event_id = :eid AND status IN ('PAID', 'CONFIRMED', 'SUCCESS')
                            """),
                            {"eid": event_id}
                        ).fetchone()
                    else:
                        bk_row = bconn.execute(
                            text("""
                                SELECT 
                                    COALESCE(SUM(total_amount), 0) as total_rev,
                                    COUNT(id) as total_bookings
                                FROM bookings 
                                WHERE status IN ('PAID', 'CONFIRMED', 'SUCCESS')
                            """)
                        ).fetchone()

                    if bk_row:
                        result["total_revenue"] = float(bk_row.total_rev or 0)
                        result["paid_orders"] = int(bk_row.total_bookings or 0)

                    # count paid tickets
                    if event_id:
                        tk_row = bconn.execute(
                            text("""
                                SELECT COUNT(t.id) as sold_count
                                FROM tickets t
                                JOIN bookings b ON t.booking_id = b.id
                                WHERE b.event_id = :eid AND b.status IN ('PAID', 'CONFIRMED', 'SUCCESS')
                            """),
                            {"eid": event_id}
                        ).fetchone()
                    else:
                        tk_row = bconn.execute(
                            text("SELECT COUNT(id) as sold_count FROM tickets WHERE status IN ('PAID', 'ACTIVE', 'ISSUED')")
                        ).fetchone()

                    if tk_row:
                        result["tickets_sold"] = int(tk_row.sold_count or 0)

            # calculate occupancy rate
            if result["total_capacity"] > 0:
                result["occupancy_rate"] = round((result["tickets_sold"] / result["total_capacity"]) * 100, 1)

        except Exception as e:
            result["error"] = str(e)

        return result

    # tool 2: query showtime capacity
    def _tool_showtime_capacity(self, event_id: Optional[str], event_title: Optional[str]) -> Dict[str, Any]:
        """Truy vấn các suất diễn và tỷ lệ lấp đầy theo từng hạng vé"""
        engine = event_engine or sql_engine.db._engine
        result = {
            "event_id": event_id,
            "event_title": event_title or "Sự kiện",
            "showtimes": []
        }

        try:
            with engine.connect() as conn:
                if event_id:
                    st_rows = conn.execute(
                        text("SELECT id, name, start_time, end_time, sale_start_time, sale_end_time FROM event_showtimes WHERE event_id = :eid ORDER BY start_time ASC"),
                        {"eid": event_id}
                    ).fetchall()
                else:
                    st_rows = conn.execute(
                        text("SELECT id, name, start_time, end_time, sale_start_time, sale_end_time FROM event_showtimes ORDER BY start_time ASC LIMIT 5")
                    ).fetchall()

                tt_engine = ticket_engine or engine
                with tt_engine.connect() as tconn:
                    for st in st_rows:
                        # ticket types belong to this showtime
                        tt_rows = tconn.execute(
                            text("SELECT id, name, price, total_quantity, ticket_type_status FROM ticket_types WHERE showtime_id = :stid"),
                            {"stid": st.id}
                        ).fetchall()

                        tiers = []
                        total_cap = 0
                        for t in tt_rows:
                            cap = int(t.total_quantity or 0)
                            total_cap += cap
                            tiers.append({
                                "tier_name": t.name,
                                "price": float(t.price or 0),
                                "capacity": cap,
                                "status": t.ticket_type_status
                            })

                        result["showtimes"].append({
                            "showtime_id": st.id,
                            "showtime_name": st.name or "Suất diễn chính",
                            "start_time": str(st.start_time) if st.start_time else "",
                            "end_time": str(st.end_time) if st.end_time else "",
                            "total_capacity": total_cap,
                            "tiers": tiers
                        })
        except Exception as e:
            result["error"] = str(e)

        return result

    # tool 3: recommend voucher strategy
    def _tool_voucher_recommendation(self, analytics_data: Dict[str, Any], user_goal: str) -> Dict[str, Any]:
        """Tính toán và gợi ý chiến lược voucher dựa trên tỷ lệ lấp đầy và mục tiêu bán vé"""
        occupancy = analytics_data.get("occupancy_rate", 0)
        event_title = analytics_data.get("event_title", "Sự kiện")
        total_capacity = analytics_data.get("total_capacity", 0)
        tickets_sold = analytics_data.get("tickets_sold", 0)
        remaining = max(0, total_capacity - tickets_sold)

        prompt = PromptTemplate.from_template(
            "Bạn là Chuyên gia Tối ưu Doanh thu Sự kiện (Revenue Management Specialist) của HaloTicket.\n"
            "Dưới đây là thông số thực tế của sự kiện:\n"
            "- Tên sự kiện: {event_title}\n"
            "- Tổng sức chứa ghế: {total_capacity}\n"
            "- Số vé đã bán: {tickets_sold}\n"
            "- Tỷ lệ lấp đầy hiện tại: {occupancy}%\n"
            "- Số vé còn lại cần bán: {remaining}\n"
            "- Yêu cầu bổ sung của BTC: {user_goal}\n\n"
            "Hãy đề xuất 1 chiến lược phát hành Voucher kích cầu tối ưu nhất để tăng doanh số nhưng không gây phá giá vé.\n"
            "Trả về JSON định dạng sau (KHÔNG bọc trong markdown code block ```json):\n"
            "{{\n"
            "  \"voucher_code\": \"<Mã voucher ngắn gọn, vd: HALOBOOST15>\",\n"
            "  \"voucher_name\": \"<Tên chương trình khuyến mãi>\",\n"
            "  \"discount_percent\": <số nguyên % giảm giá, vd 10, 15, 20>,\n"
            "  \"min_order_value\": <số tiền tối thiểu VNĐ, vd 300000>,\n"
            "  \"max_usage_count\": <số lượt dùng tối đa, vd 100>,\n"
            "  \"target_segment\": \"<Đối tượng hướng đến, vd: Khách mua nhóm từ 2 vé trở lên>\",\n"
            "  \"rationale\": \"<Lý do và phân tích ngắn gọn>\"\n"
            "}}"
        )

        try:
            chain = prompt | self.fast_llm
            res = chain.invoke({
                "event_title": event_title,
                "total_capacity": total_capacity,
                "tickets_sold": tickets_sold,
                "occupancy": occupancy,
                "remaining": remaining,
                "user_goal": user_goal or "Kích cầu tăng tốc độ bán vé"
            })
            content = str(res.content).replace("```json", "").replace("```", "").strip()
            data = json.loads(content)
            return data
        except Exception as e:
            return {
                "voucher_code": "BOOSTNOW10",
                "voucher_name": "Ưu đãi tri ân kích cầu",
                "discount_percent": 10,
                "min_order_value": 200000,
                "max_usage_count": 50,
                "target_segment": "Khách hàng thân thiết",
                "rationale": f"Hệ thống tự động đề xuất ưu đãi 10% để thúc đẩy tỷ lệ lấp đầy hiện tại ({occupancy}%)."
            }

    # tool 4: generate announcement
    def _tool_generate_announcement(self, topic: str, event_title: str) -> str:
        """Soạn thảo thông báo / email trang trọng, chuyên nghiệp gửi khán giả"""
        prompt = PromptTemplate.from_template(
            "Bạn là Giám đốc Truyền thông của Ban tổ chức sự kiện tại HaloTicket.\n"
            "Sự kiện: '{event_title}'.\n"
            "Chủ đề thông báo cần soạn: '{topic}'.\n\n"
            "Hãy viết một bức THƯ / THÔNG BÁO CHÍNH THỨC gửi tới toàn thể quý khán giả:\n"
            "- Văn phong: Chuyên nghiệp, ấm áp, chu đáo, tôn trọng khán giả.\n"
            "- Có tiêu đề thông báo trang trọng.\n"
            "- Nêu rõ nội dung chính, hướng dẫn cụ thể (thời gian, địa điểm, cách thức check-in hoặc chính sách hỗ trợ).\n"
            "- Lời cảm ơn và chữ ký đại diện Ban Tổ Chức.\n"
            "- Định dạng Markdown đẹp mắt, có gạch đầu dòng rõ ràng."
        )
        try:
            chain = prompt | self.smart_llm
            res = chain.invoke({"event_title": event_title, "topic": topic})
            content = res.content
            if isinstance(content, list):
                content = "".join([p.get("text", "") if isinstance(p, dict) else str(p) for p in content])
            return str(content).strip()
        except Exception as e:
            return f"Không thể soạn thảo thông báo: {str(e)}"

    # main agent chat pipeline
    def chat(
        self,
        message: str,
        history: Optional[List[Dict[str, str]]] = None,
        event_id: Optional[str] = None,
        event_title: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Quy trình xử lý Agent Pipeline chuẩn cho Ban Tổ Chức:
        - Cache Check -> Intent Analysis -> Tool Execution -> Synthesis Reasoning -> Cache Save.
        """
        start_time = time.time()
        clean_msg = message.strip()
        msg_lower = clean_msg.lower()

        # 0. cache check
        cache_key = hashlib.md5(f"{msg_lower}:{event_id or ''}".encode()).hexdigest()
        cached_result = self.cache.get(cache_key)
        if cached_result:
            result_copy = dict(cached_result)
            result_copy["is_cached"] = True
            result_copy["execution_time_ms"] = int((time.time() - start_time) * 1000) or 5
            return result_copy

        pipeline_steps = []
        actions = []

        # 1. pipeline step 1: intent & planning
        step1_start = time.time()
        intent = "GENERAL"
        tool_label = ""

        if any(k in msg_lower for k in ["doanh thu", "tiền bán vé", "doanh số", "vé đã bán", "bán được bao nhiêu", "báo cáo tài chính", "lợi nhuận"]):
            intent = "ANALYTICS"
            tool_label = "Truy vấn doanh thu & số vé thực tế từ hệ thống"
        elif any(k in msg_lower for k in ["suất diễn", "lấp đầy", "tỷ lệ lấp đầy", "ghế trống", "sức chứa", "còn bao nhiêu ghế", "hạng vé", "occupancy"]):
            intent = "SHOWTIME_CAPACITY"
            tool_label = "Kiểm tra tình trạng sức chứa & tỷ lệ lấp đầy các suất diễn"
        elif any(k in msg_lower for k in ["voucher", "khuyến mãi", "mã giảm giá", "giảm giá", "kích cầu", "chiến lược giá", "promotion"]):
            intent = "VOUCHER"
            tool_label = "Phân tích tốc độ bán vé & lập chiến lược phát hành voucher"
        elif any(k in msg_lower for k in ["thông báo", "soạn email", "viết bài", "thư gửi khán giả", "dời lịch", "hoãn lịch", "tri ân", "nhắc nhở check-in"]):
            intent = "ANNOUNCEMENT"
            tool_label = "Kích hoạt mô hình soạn thảo truyền thông sự kiện"
        elif any(k in msg_lower for k in ["top", "khách hàng", "sql", "thống kê", "phân tích", "cơ sở dữ liệu", "db", "tổng"]):
            intent = "SQL_QUERY"
            tool_label = "Thực thi phân tích dữ liệu SQL an toàn (Read-Only SQLEngine)"
        else:
            intent = "GENERAL"
            tool_label = "Tư vấn & Hướng dẫn nghiệp vụ quản trị sự kiện"

        pipeline_steps.append({
            "step": "INTENT",
            "name": "Xác định mục tiêu & Lập kế hoạch",
            "detail": f"Nhận diện yêu cầu: [{intent}] - {tool_label}",
            "status": "COMPLETED",
            "duration_ms": int((time.time() - step1_start) * 1000)
        })

        # 2. pipeline step 2: tool execution
        step2_start = time.time()
        tool_output = {}

        if intent == "ANALYTICS":
            tool_output = self._tool_event_analytics(event_id, event_title)
            # add structured action for quick visual preview
            actions.append({
                "action": "DISPLAY_KPI_SUMMARY",
                "database_data": {
                    "total_revenue": tool_output.get("total_revenue", 0),
                    "tickets_sold": tool_output.get("tickets_sold", 0),
                    "total_capacity": tool_output.get("total_capacity", 0),
                    "paid_orders": tool_output.get("paid_orders", 0),
                    "occupancy_rate": tool_output.get("occupancy_rate", 0)
                }
            })

        elif intent == "SHOWTIME_CAPACITY":
            tool_output = self._tool_showtime_capacity(event_id, event_title)
            actions.append({
                "action": "DISPLAY_SHOWTIMES_TABLE",
                "database_data": tool_output.get("showtimes", [])
            })

        elif intent == "VOUCHER":
            analytics = self._tool_event_analytics(event_id, event_title)
            voucher_data = self._tool_voucher_recommendation(analytics, clean_msg)
            tool_output = {
                "analytics": analytics,
                "voucher_recommendation": voucher_data
            }
            actions.append({
                "action": "DISPLAY_VOUCHER_CARD",
                "database_data": voucher_data
            })

        elif intent == "ANNOUNCEMENT":
            announcement_text = self._tool_generate_announcement(clean_msg, event_title or "Sự kiện")
            tool_output = {"announcement": announcement_text}

        elif intent == "SQL_QUERY":
            sql_res = sql_engine.analyze_data(clean_msg)
            tool_output = sql_res
            if "data" in sql_res and sql_res["data"]:
                actions.append({
                    "action": "DISPLAY_DATA_TABLE",
                    "database_data": sql_res["data"]
                })

        pipeline_steps.append({
            "step": "TOOL_EXECUTION",
            "name": "Kích hoạt công cụ Agent",
            "detail": f"Đã chạy công cụ: {tool_label} thành công",
            "status": "COMPLETED",
            "duration_ms": int((time.time() - step2_start) * 1000)
        })

        # 3. pipeline step 3: synthesis & reasoning
        step3_start = time.time()
        final_reply = ""

        try:
            if intent == "ANNOUNCEMENT":
                final_reply = tool_output.get("announcement", "Đã soạn thảo thông báo thành công.")
            elif intent == "SQL_QUERY":
                final_reply = tool_output.get("reply", "Đã truy vấn và phân tích dữ liệu.")
            elif intent == "VOUCHER":
                vr = tool_output.get("voucher_recommendation", {})
                ana = tool_output.get("analytics", {})
                final_reply = (
                    f"### Đề xuất Chiến Lược Voucher Kích Cầu\n\n"
                    f"- **Tình hình hiện tại:** Sự kiện `{ana.get('event_title', 'Sự kiện')}` đã bán được **{ana.get('tickets_sold', 0)} / {ana.get('total_capacity', 0)}** vé "
                    f"(Tỷ lệ lấp đầy: **{ana.get('occupancy_rate', 0)}%**).\n\n"
                    f"#### Thông tin Voucher đề xuất:\n"
                    f"- **Mã khuyến mãi:** `{vr.get('voucher_code', 'BOOST10')}`\n"
                    f"- **Chương trình:** {vr.get('voucher_name', 'Kích cầu bán vé')}\n"
                    f"- **Mức chiết khấu:** Giảm **{vr.get('discount_percent', 10)}%**\n"
                    f"- **Đơn hàng tối thiểu:** {vr.get('min_order_value', 0):,} VNĐ\n"
                    f"- **Số lượng phát hành:** {vr.get('max_usage_count', 100)} lượt\n"
                    f"- **Đối tượng mục tiêu:** {vr.get('target_segment', 'Khách hàng quan tâm')}\n\n"
                    f"**Chiến lược & Rationale:**\n{vr.get('rationale', '')}\n\n"
                    f"*Ban tổ chức có thể vào mục Quản lý Voucher để kích hoạt chiến dịch này ngay.*"
                )
            elif intent == "ANALYTICS":
                rev = tool_output.get("total_revenue", 0)
                sold = tool_output.get("tickets_sold", 0)
                cap = tool_output.get("total_capacity", 0)
                orders = tool_output.get("paid_orders", 0)
                occ = tool_output.get("occupancy_rate", 0)
                title = tool_output.get("event_title", "Sự kiện")

                # generate AI synthesis commentary
                synth_prompt = PromptTemplate.from_template(
                    "Bạn là Giám đốc Tài chính & Vận hành Sự kiện tại HaloTicket.\n"
                    "Thông số bán vé của sự kiện '{title}':\n"
                    "- Tổng doanh thu thực tế: {revenue:,.0f} VNĐ\n"
                    "- Số vé đã bán: {sold} / {capacity} vé\n"
                    "- Số đơn hàng thành công: {orders}\n"
                    "- Tỷ lệ lấp đầy: {occ}%\n\n"
                    "Người dùng hỏi: '{question}'\n\n"
                    "Hãy viết báo cáo ngắn gọn (3-4 gạch đầu dòng):\n"
                    "1. Tóm tắt tình hình doanh thu và tiến độ lấp đầy.\n"
                    "2. Đánh giá tốc độ bán vé (nhanh/trung bình/chậm).\n"
                    "3. Đề xuất 1-2 hành động tiếp theo cho Ban tổ chức (ví dụ: đẩy marketing, mở thêm hạng vé, hoặc phát hành voucher)."
                )
                synth_chain = synth_prompt | self.fast_llm
                synth_res = synth_chain.invoke({
                    "title": title,
                    "revenue": rev,
                    "sold": sold,
                    "capacity": cap,
                    "orders": orders,
                    "occ": occ,
                    "question": clean_msg
                })
                commentary = synth_res.content
                if isinstance(commentary, list):
                    commentary = "".join([p.get("text", "") if isinstance(p, dict) else str(p) for p in commentary])

                final_reply = (
                    f"### Báo Cáo Doanh Thu & Bán Vé: {title}\n\n"
                    f"- **Tổng doanh thu:** **{rev:,.0f} VNĐ**\n"
                    f"- **Số vé đã bán:** **{sold} / {cap}** ({occ}% tổng sức chứa)\n"
                    f"- **Tổng số đơn hàng:** **{orders}** đơn thành công\n\n"
                    f"**Nhận xét & Khuyến nghị quản trị:**\n{commentary.strip()}"
                )

            elif intent == "SHOWTIME_CAPACITY":
                showtimes = tool_output.get("showtimes", [])
                title = tool_output.get("event_title", "Sự kiện")
                if not showtimes:
                    final_reply = f"Hiện tại chưa có suất diễn nào được ghi nhận cho sự kiện '{title}'."
                else:
                    lines = [f"### Tình Trạng Suất Diễn & Sức Chứa: {title}\n"]
                    for st in showtimes:
                        lines.append(f"#### {st.get('showtime_name', 'Suất diễn')}")
                        lines.append(f"- **Thời gian:** {st.get('start_time', 'Chưa xác định')}")
                        lines.append(f"- **Tổng sức chứa:** {st.get('total_capacity', 0)} vé")
                        if st.get("tiers"):
                            lines.append("  - **Chi tiết các hạng vé:**")
                            for tier in st.get("tiers", []):
                                lines.append(f"    + **{tier['tier_name']}**: {tier['price']:,.0f} VNĐ (Số lượng: {tier['capacity']}) - Trạng thái: `{tier['status']}`")
                        lines.append("")
                    final_reply = "\n".join(lines)

            else: # GENERAL
                general_prompt = PromptTemplate.from_template(
                    "Bạn là Trợ lý AI Quản Trị Thông Minh dành riêng cho Ban Tổ Chức (Organizer) của HaloTicket.\n"
                    "Câu hỏi của Ban Tổ Chức: '{question}'.\n"
                    "Hãy tư vấn một cách am hiểu, chu đáo và hữu ích về các nghiệp vụ vận hành sự kiện, phân phối vé, hoặc hướng dẫn họ sử dụng các tính năng quản trị."
                )
                res = (general_prompt | self.fast_llm).invoke({"question": clean_msg})
                content = res.content
                if isinstance(content, list):
                    content = "".join([p.get("text", "") if isinstance(p, dict) else str(p) for p in content])
                final_reply = str(content).strip()

        except Exception as e:
            final_reply = f"Đã thu thập dữ liệu nhưng gặp sự cố khi định dạng phản hồi: {str(e)}"

        pipeline_steps.append({
            "step": "SYNTHESIS",
            "name": "Tổng hợp chỉ số & Đề xuất giải pháp",
            "detail": "Hoàn tất suy luận và xây dựng phản hồi quản trị chuyên sâu",
            "status": "COMPLETED",
            "duration_ms": int((time.time() - step3_start) * 1000)
        })

        total_exec_time = int((time.time() - start_time) * 1000)

        response_payload = {
            "reply": final_reply,
            "pipeline_steps": pipeline_steps,
            "actions": actions,
            "is_cached": False,
            "execution_time_ms": total_exec_time
        }

        # save cache for similar questions
        self.cache.set(cache_key, response_payload)

        return response_payload


organizer_agent = OrganizerAgent()
