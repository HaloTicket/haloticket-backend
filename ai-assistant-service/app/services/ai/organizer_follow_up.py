"""One next question at a time, derived from the organizer's own inputs."""
import re
import unicodedata


_AUDIENCE = re.compile(r"\b(khan gia|doi tuong|nhom muc tieu|audience|target group)\s*(chinh\s*)?(la\s+|is\s+|[:=])|\b(sinh vien|gia dinh|students|families)\b")
_AMOUNT = re.compile(r"\b\d[\d., ]*\s*(trieu|nghin|ngan|dong|vnd|million|thousand|usd)\b")
_GOAL = re.compile(r"\b(muc tieu|goal|objective)\s*(la\s+|is\s+|[:=])|\b(tang nhan dien|ban het ve|increase awareness|sell out)\b")
_PROMOTION = re.compile(r"\b(quang ba|truyen thong|marketing|promotion|campaign|voucher)\b")
FOLLOW_UP_INPUT = re.compile(
    r"\b(khan gia|doi tuong|sinh vien|gia dinh|ngan sach|muc tieu|audience|students|families|budget|goal|"
    r"trieu|nghin|million|vnd|quang cao|advertising)\b"
)


def _question(prompt, options):
    return {"question": prompt, "options": [{"label": label, "message": message} for label, message in options]}


def advice_follow_up(context: str, locale: str):
    en = locale == "en"
    if not _AUDIENCE.search(context):
        return _question(
            "Who should this plan reach first?" if en else "Bạn muốn tiếp cận nhóm khán giả nào trước?",
            [("Students", "The main audience is students"), ("Families", "The main audience is families")] if en
            else [("Sinh viên", "Khán giả chính là sinh viên"), ("Gia đình", "Khán giả chính là các gia đình")],
        )
    if _PROMOTION.search(context) and not (_AMOUNT.search(context) or "khong dung quang cao tra phi" in context
                                           or "no paid advertising" in context):
        return _question(
            "How much can you spend on promotion?" if en else "Bạn dự kiến dành bao nhiêu ngân sách cho quảng bá?",
            [("Organic only", "Use no paid advertising")] if en
            else [("Chỉ kênh miễn phí", "Không dùng quảng cáo trả phí")],
        )
    if not _GOAL.search(context):
        return _question(
            "What is the main goal for this event?" if en else "Bạn muốn ưu tiên mục tiêu nào cho sự kiện này?",
            [("Ticket sales", "The goal is to sell more tickets"), ("Awareness", "The goal is to increase awareness")] if en
            else [("Bán thêm vé", "Mục tiêu là bán thêm vé"), ("Tăng nhận diện", "Mục tiêu là tăng nhận diện")],
        )
    return None


def analytics_follow_up(message: str, question: str, locale: str, metrics: list[dict]):
    """Offer a valid query refinement that keeps the user's original event and period."""
    en = locale == "en"
    refinements = (
        (r"\b(doanh thu|revenue)\b", "ticket_sales_revenue", "doanh thu theo ngày", "revenue by day"),
        (r"\b(check.?in|attendance)\b", "check_ins", "lượt check-in theo ngày", "check-ins by day"),
        (r"\b(don (?:da )?thanh toan|don hang|paid orders)\b", "paid_orders", "đơn đã thanh toán theo ngày", "paid orders by day"),
        (r"\b(ve da ban|ve ban|so ve|tong quan|tickets sold|ticket sales|overview)\b",
         "tickets_sold", "số vé bán theo ngày", "ticket sales by day"),
    )
    refinement = next((entry for entry in refinements if re.search(entry[0], question)), None)
    if refinement is None:
        return None
    _, key, vi_label, en_label = refinement
    measured = next((metric for metric in metrics if metric["key"] == key), None)
    if not measured or measured["completeness"] != "complete":
        return None
    label = en_label if en else vi_label
    text = f"{message.rstrip(' .?!')} — {'show' if en else 'xem'} {label}"
    if len(text) > 1000:
        return None
    return _question(
        f"Would a breakdown of {label} help you spot changes?" if en else f"Bạn có muốn xem {label} để tìm mốc biến động không?",
        [("View by day" if en else "Xem theo ngày", text)],
    )


def _norm_question(value: str) -> str:
    folded = "".join(
        c for c in unicodedata.normalize("NFD", (value or "").casefold().replace("đ", "d"))
        if not unicodedata.combining(c)
    )
    return re.sub(r"\s+", " ", re.sub(r"[?.!]+$", "", folded)).strip()


_PURE_GREETING = re.compile(r"^(?:hi|hello|hey|alo|chao|xin chao|chao ban|hello ad|hi ad)$")


def next_suggested_questions(
    *,
    scope: str,
    locale: str,
    question: str,
    history: list[dict] | None = None,
    advice: bool = False,
    list_events: bool = False,
    daily: bool = False,
    totals: list[dict] | None = None,
    warnings: list[dict] | None = None,
    has_events: bool = True,
    can_view_finance: bool = True,
) -> list[str]:
    """Return up to 3 contextual next questions for the composer suggestion bar."""
    en = locale == "en"
    is_event = scope == "event"

    starters = (
        [
            "How many tickets are left for each showtime?",
            "Which ticket tier has the highest occupancy?",
            "How many attendees have checked in?",
        ] if en else [
            "Mỗi suất diễn còn bao nhiêu vé?",
            "Hạng vé nào có tỷ lệ lấp đầy cao nhất?",
            "Đã có bao nhiêu khách check-in?",
        ]
    ) if is_event else (
        [
            "Which events sold the most and fewest tickets in the last 7 days?",
            "How many tickets have I sold, and what was the total revenue?" if can_view_finance
            else "How many tickets have I sold in the last 7 days?",
            "Which events need attention or have alerts?",
        ] if en else [
            "Sự kiện nào bán chạy nhất và bán chậm nhất trong 7 ngày qua?",
            "Tôi đã bán được bao nhiêu vé và tổng doanh thu là bao nhiêu?" if can_view_finance
            else "Tôi đã bán được bao nhiêu vé trong 7 ngày qua?",
            "Sự kiện nào đang cần chú ý hoặc có cảnh báo?",
        ]
    )

    if not has_events:
        return starters[:3]

    asked = {_norm_question(question)}
    for entry in (history or [])[-6:]:
        if entry.get("role") == "user" and isinstance(entry.get("content"), str):
            asked.add(_norm_question(entry["content"]))

    q = _norm_question(question)
    results: list[str] = []
    seen: set[str] = set()

    def push(candidate: str):
        if len(results) >= 3:
            return
        key = _norm_question(candidate)
        if not key or key in seen or key in asked:
            return
        if not can_view_finance and re.search(r"\b(doanh thu|revenue)\b", key):
            return
        seen.add(key)
        results.append(candidate)

    if _PURE_GREETING.match(q):
        for item in starters:
            push(item)
    else:
        asked_warnings = bool(re.search(r"\b(can chu y|canh bao|thieu ve|chua co ve|attention|alert|missing tickets)\b", q))
        if warnings and not asked_warnings:
            push(
                ("Are there any paid orders missing tickets?" if en else "Có đơn nào đã thanh toán mà thiếu vé không?")
                if is_event
                else ("Which events need attention or have alerts?" if en else "Sự kiện nào đang cần chú ý hoặc có cảnh báo?")
            )

        if daily:
            pool = (
                [
                    "Compare revenue this week vs last week?" if can_view_finance else "Compare tickets sold this week vs last week?",
                    "Which ticket tier has the highest occupancy?",
                    "What voucher strategy works best for slow showtimes?",
                ] if en else [
                    "So sánh doanh thu tuần này và tuần trước?" if can_view_finance else "So sánh số vé bán tuần này và tuần trước?",
                    "Hạng vé nào có tỷ lệ lấp đầy cao nhất?",
                    "Đề xuất chiến lược voucher cho suất bán chậm?",
                ]
            ) if is_event else (
                [
                    "Compare revenue this week vs last week?" if can_view_finance else "Compare tickets sold this week vs last week?",
                    "Which events sold the most and fewest tickets in the last 7 days?",
                    "How should I allocate marketing budget across upcoming events?",
                ] if en else [
                    "So sánh doanh thu tuần này và tuần trước?" if can_view_finance else "So sánh số vé bán tuần này và tuần trước?",
                    "Sự kiện nào bán chạy nhất và bán chậm nhất trong 7 ngày qua?",
                    "Tư vấn phân bổ ngân sách quảng bá cho các sự kiện sắp tới?",
                ]
            )
            for item in pool:
                push(item)
        elif advice:
            pool = (
                [
                    "Create a 7-day promotion plan for this event?",
                    "Draft check-in instructions for attendees?",
                    "What voucher strategy works best for slow showtimes?",
                ] if en else [
                    "Lập kế hoạch quảng bá trong 7 ngày tới cho sự kiện này?",
                    "Soạn thông báo hướng dẫn check-in cho khán giả?",
                    "Đề xuất chiến lược voucher cho suất bán chậm?",
                ]
            ) if is_event else (
                [
                    "Create a 7-day promotion plan?",
                    "Which events sold the most and fewest tickets in the last 7 days?",
                    "Which events need attention or have alerts?",
                ] if en else [
                    "Lập kế hoạch quảng bá chi tiết trong 7 ngày tới?",
                    "Sự kiện nào bán chạy nhất và bán chậm nhất trong 7 ngày qua?",
                    "Sự kiện nào đang cần chú ý hoặc có cảnh báo?",
                ]
            )
            for item in pool:
                push(item)
        elif list_events:
            pool = [
                "Which events am I managing?",
                "How many tickets have I sold, and what was the total revenue?",
                "Which events need attention or have alerts?",
            ] if en else [
                "Tôi đang quản lý những sự kiện nào?",
                "Tôi đã bán được bao nhiêu vé và tổng doanh thu là bao nhiêu?",
                "Sự kiện nào đang cần chú ý hoặc có cảnh báo?",
            ]
            for item in pool:
                push(item)
        elif re.search(r"\b(check.?in|tham gia|attendance)\b", q):
            pool = [
                "Show daily check-ins over the last 7 days?",
                "How many tickets are left for each showtime?",
                "Draft an opening-time and check-in reminder?",
            ] if en else [
                "Xem lượt check-in theo ngày trong 7 ngày qua?",
                "Mỗi suất diễn còn bao nhiêu vé?",
                "Soạn thông báo hướng dẫn giờ mở cổng và check-in?",
            ]
            for item in pool:
                push(item)
        elif re.search(r"\b(ton ve|con bao nhieu ve|hang ve|lap day|remaining|occupancy|tier|stock)\b", q):
            pool = [
                "Are there any paid orders missing tickets?",
                "Compare revenue across ticket tiers?",
                "What voucher strategy works best for slow showtimes?",
            ] if en else [
                "Có đơn nào đã thanh toán mà thiếu vé không?",
                "So sánh doanh thu theo từng hạng vé?",
                "Đề xuất chiến lược voucher cho suất bán chậm?",
            ]
            for item in pool:
                push(item)
        elif asked_warnings:
            pool = (
                [
                    "How many tickets are left for each showtime?",
                    "What voucher strategy works best for slow showtimes?",
                    "Draft check-in instructions for attendees?",
                ] if en else [
                    "Mỗi suất diễn còn bao nhiêu vé?",
                    "Đề xuất chiến lược voucher cho suất bán chậm?",
                    "Soạn thông báo hướng dẫn check-in cho khán giả?",
                ]
            ) if is_event else (
                [
                    "How many tickets have I sold, and what was the total revenue?",
                    "How should I allocate marketing budget across upcoming events?",
                    "Are any of my events still pending approval?",
                ] if en else [
                    "Tôi đã bán được bao nhiêu vé và tổng doanh thu là bao nhiêu?",
                    "Tư vấn phân bổ ngân sách quảng bá cho các sự kiện sắp tới?",
                    "Có sự kiện nào của tôi đang chờ duyệt không?",
                ]
            )
            for item in pool:
                push(item)
        elif re.search(r"\b(ban chay|ban cham|ban tot|cao nhat|thap nhat|best|slow|most|fewest)\b", q):
            pool = [
                "How can I boost sales for slow-selling events?",
                "Show daily ticket sales over the last 7 days?",
                "Compare tickets sold this week vs last week?",
            ] if en else [
                "Tư vấn cách tăng vé cho sự kiện bán chậm?",
                "Xem số vé bán theo ngày trong 7 ngày qua?",
                "So sánh số vé bán tuần này và tuần trước?",
            ]
            for item in pool:
                push(item)
        elif re.search(r"\b(doanh thu|revenue|ve da ban|tong hop|tong quan|tickets sold|overview)\b", q):
            pool = (
                [
                    "Show daily revenue over the last 7 days?" if can_view_finance else "Show daily ticket sales over the last 7 days?",
                    "Which ticket tier has the highest occupancy?",
                    "How many tickets are left for each showtime?",
                ] if en else [
                    "Xem doanh thu theo ngày trong 7 ngày qua?" if can_view_finance else "Xem số vé bán theo ngày trong 7 ngày qua?",
                    "Hạng vé nào có tỷ lệ lấp đầy cao nhất?",
                    "Mỗi suất diễn còn bao nhiêu vé?",
                ]
            ) if is_event else (
                [
                    "Show daily revenue over the last 7 days?" if can_view_finance else "Show daily ticket sales over the last 7 days?",
                    "Compare revenue this week vs last week?" if can_view_finance else "Compare tickets sold this week vs last week?",
                    "Which events sold the most and fewest tickets in the last 7 days?",
                ] if en else [
                    "Xem doanh thu theo ngày trong 7 ngày qua?" if can_view_finance else "Xem số vé bán theo ngày trong 7 ngày qua?",
                    "So sánh doanh thu tuần này và tuần trước?" if can_view_finance else "So sánh số vé bán tuần này và tuần trước?",
                    "Sự kiện nào bán chạy nhất và bán chậm nhất trong 7 ngày qua?",
                ]
            )
            for item in pool:
                push(item)

    secondary = (
        [
            "How can I increase revenue for this event?",
            "How should I price tickets for this event?",
            "How can I promote this event over the next 7 days?",
        ] if en else [
            "Làm sao để tăng doanh thu cho sự kiện này?",
            "Tôi nên đặt giá vé thế nào?",
            "Tôi nên quảng bá sự kiện thế nào trong 7 ngày tới?",
        ]
    ) if is_event else (
        [
            "Are any of my events still pending approval?",
            "Which upcoming events should I spend more on promoting?",
            "Which events am I managing?",
        ] if en else [
            "Có sự kiện nào của tôi đang chờ duyệt không?",
            "Tôi nên tăng ngân sách quảng bá cho sự kiện nào sắp tới?",
            "Tôi đang quản lý những sự kiện nào?",
        ]
    )

    for item in [*starters, *secondary]:
        push(item)

    return results or starters[:3]
