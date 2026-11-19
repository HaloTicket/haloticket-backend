"""Chatbot khách hàng: tìm kiếm có lọc, không dấu, ngữ cảnh đặt vé, vòng lặp công cụ, bản gọn cho mô hình, ngày hiện tại.
Không dùng mô hình, mạng hay cơ sở dữ liệu thật."""
import json
from datetime import datetime
from decimal import Decimal

import pytest

import customer_fakes as fakes
from app.services.ai import booking_agent as ba
from app.services.ai import customer_sql as csql
from app.services.ai import tools

SLUG = "nxnx37-hoc-vien-phep-thuat"


# ------------------------------------------------------------------ dùng chung
def agent_with(monkeypatch, replies, event=None, ticket=None):
    agent = ba.BookingAgent()
    calls = []

    def fake_invoke(profile, messages, *, actor=None, tools=None, tier=None):
        calls.append({"messages": list(messages), "tier": tier})
        return replies.pop(0)

    monkeypatch.setattr(ba, "ai_invoke", fake_invoke)
    engines = fakes.Engines(event=event or [], ticket=ticket or [])
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    monkeypatch.setattr(agent, "_vector_events", lambda text: [])
    return agent, calls, engines


def call(name, args, call_id="c1"):
    return {"name": name, "args": args, "id": call_id}


class FakeTool:
    def __init__(self, result):
        self.result, self.invocations = result, []

    def invoke(self, args):
        self.invocations.append(args)
        return self.result


# ------------------------------------------------------------------ SQL dùng chung
def test_valid_event_filter_stops_offering_events_whose_sale_has_ended():
    assert "e.sale_end_time > now()" in csql.VALID_EVENT_FILTER
    assert "e.is_private IS NOT TRUE" in csql.VALID_EVENT_FILTER
    assert "e.event_status IN ('LIVE', 'APPROVED')" in csql.VALID_EVENT_FILTER


def test_folding_removes_accents_and_the_sql_translate_strings_line_up():
    assert csql.fold_text("Đà Lạt – HÀ NỘI Hoàng Dũng") == "da lat – ha noi hoang dung"
    assert len(csql.ACCENT_FROM) == len(csql.ACCENT_TO)
    # mỗi chữ có dấu dạng thường lẫn hoa đều đổi thành chữ thường không dấu
    for src, dst in zip(csql.ACCENT_FROM, csql.ACCENT_TO):
        assert dst == csql.fold_text(src) and dst.isascii()
    assert "translate(" in csql.fold_sql("e.title") and "coalesce(e.title" in csql.fold_sql("e.title")


def test_city_abbreviations_expand_and_other_words_only_fold():
    assert csql.expand_token("HCM") == ["ho chi minh", "hcm"]
    assert csql.expand_token("SG") == ["sai gon", "ho chi minh"]
    assert csql.expand_token("Hoàng") == ["hoang"]
    assert csql.expand_token("  ") == []


# ------------------------------------------------------------------ search_events
def search(monkeypatch, rows=None, **args):
    engines = fakes.Engines(
        event=[("SELECT e.id FROM events e WHERE", rows if rows is not None else []),
               ("e.id IN", [fakes.row(id="ev1", title="Hoàng Dũng Live", banner_url="b.png", thumbnail_url="",
                                      location_name="Nhà hát", city="Hồ Chí Minh", dates_summary="05/12", min_price=None)])],
        ticket=[("GROUP BY event_id", [fakes.row(event_id="ev1", min_price=Decimal("800000"))])],
    )
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    return json.loads(tools.search_events.invoke(args)), engines


def test_search_folds_keywords_expands_city_abbreviations_and_filters_validity(monkeypatch):
    result, engines = search(monkeypatch, rows=[fakes.row(id="ev1")], query="Hoàng Dũng HCM", max_price=1000000, limit=3)
    sql = engines.statements[0].sql
    params = engines.statements[0].params
    assert "translate(" in sql and "is_private IS NOT TRUE" in sql and "e.min_price <= :max_price" in sql
    assert params["tok_0_0"] == "%hoang%" and params["tok_1_0"] == "%dung%"
    assert {params["tok_2_0"], params["tok_2_1"]} == {"%ho chi minh%", "%hcm%"}
    assert params["max_price"] == 1000000 and params["limit"] == 3
    # kết quả có cùng hình dạng thẻ sự kiện để giao diện hiển thị
    assert result["status"] == "show_cards" and result["applied_filters"]["query"] == "Hoàng Dũng HCM"
    cards = json.loads(result["data"])
    assert cards[0]["event_id"] == "ev1" and cards[0]["min_price"] == 800000


def test_search_by_city_and_date_range(monkeypatch):
    result, engines = search(monkeypatch, rows=[fakes.row(id="ev1")], city="Hà Nội", date_from="2026-12-05", date_to="2026-12-06")
    params = engines.statements[0].params
    assert params["city_0"] == "%ha noi%"
    assert params["from_day"] == datetime(2026, 12, 5)
    assert params["to_exclusive"] == datetime(2026, 12, 7)  # tính cả ngày cuối
    assert "e.start_time IS NOT NULL" in engines.statements[0].sql


def test_search_with_no_match_is_no_results_not_cards(monkeypatch):
    result, _ = search(monkeypatch, rows=[], query="không có gì")
    assert result["status"] == "no_results" and "data" not in result


@pytest.mark.parametrize("args,needle", [
    ({"date_from": "05/12/2026"}, "date_from"),
    ({"date_to": "xx"}, "date_to"),
    ({"date_from": "2026-12-10", "date_to": "2026-12-01"}, "date_to"),
    ({"max_price": -5}, "max_price"),
])
def test_search_refuses_bad_arguments_without_touching_the_database(monkeypatch, args, needle):
    result, engines = search(monkeypatch, rows=[fakes.row(id="ev1")], **args)
    assert result["status"] == "error" and needle in result["message"]
    assert engines.statements == []


def test_a_non_numeric_budget_from_the_model_becomes_an_error_result_not_a_crash(monkeypatch):
    search(monkeypatch, rows=[])
    result = json.loads(ba.BookingAgent()._run_tool(tools.search_events, {"max_price": "rẻ"}))
    assert result["status"] == "error"


def test_search_limit_is_clamped(monkeypatch):
    _, engines = search(monkeypatch, rows=[], limit=500)
    assert engines.statements[0].params["limit"] == tools.MAX_SEARCH_RESULTS
    _, engines = search(monkeypatch, rows=[], limit=0)
    assert engines.statements[0].params["limit"] == 6


def test_search_treats_like_wildcards_as_text(monkeypatch):
    _, engines = search(monkeypatch, rows=[], query="100%")
    assert engines.statements[0].params["tok_0_0"] == "%100\\%%"


def test_search_results_reach_the_web_as_event_cards():
    agent = ba.BookingAgent()
    _, action, show_when = agent.dispatch["search_events"]
    assert action == "SHOW_EVENT_CARDS" and show_when == {"show_cards"}


# ------------------------------------------------------------------ ticket types chỉ cho sự kiện còn hiệu lực
def test_ticket_types_are_not_returned_for_drafts_or_private_events(monkeypatch):
    engines = fakes.Engines(event=[("FROM events e", [])])
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    result = json.loads(tools.show_ticket_types.invoke({"event_id": "draft-1"}))
    assert result["status"] == "error"
    assert "is_private IS NOT TRUE" in engines.sql_of("event")[0]
    assert engines.sql_of("ticket") == []  # không đọc vé của sự kiện không hợp lệ


# ------------------------------------------------------------------ xác nhận đặt vé: hạng vé có ghế ngồi
ZONES = {"z1": {"name": "VIP", "tierId": "tt-vip"}, "z2": {"name": "Standard", "tierId": "tt-std"}}
SEATS = [{"id": "z1-A-1", "zoneId": "z1", "row": "A", "number": 1, "status": "available"}]


def confirmation_engines(monkeypatch):
    rows = [
        fakes.row(id="tt-vip", showtime_id=None, name="VIP", price=Decimal("1500000"), ticket_type_status="ACTIVE", min_per_order=1, max_per_order=6),
        fakes.row(id="tt-std", showtime_id=None, name="Standard", price=Decimal("500000"), ticket_type_status="ACTIVE", min_per_order=1, max_per_order=10),
    ]
    layout = json.dumps({"zones": ZONES, "seats": SEATS})
    engines = fakes.Engines(
        event=[("FROM events e", [fakes.row(id="ev", title="Real")]), ("event_showtimes", [])],
        ticket=[("FROM seat_layouts", [fakes.row(draft_json=layout)]), ("FROM ticket_types", rows)],
    )
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)


def confirm(payload):
    return json.loads(tools.request_user_confirmation.invoke({"action_type": "BOOK_TICKETS", "data": json.dumps(payload)}))


def test_a_seated_tier_cannot_be_confirmed_without_real_seats(monkeypatch):
    confirmation_engines(monkeypatch)
    result = confirm({"event_id": "ev", "tickets": [{"tier_name": "VIP", "quantity": 2}]})
    assert result["status"] == "error" and "VIP" in result["message"] and "ghế" in result["message"]


def test_a_seated_tier_with_matching_seats_is_confirmed_and_flagged(monkeypatch):
    confirmation_engines(monkeypatch)
    result = confirm({"event_id": "ev", "tickets": [{"tier_name": "VIP", "quantity": 2}], "seats": ["A1", "A2"]})
    data = json.loads(result["data"])
    assert result["status"] == "waiting_for_user" and data["tickets"][0]["is_seated"] is True
    assert data["seats"] == ["A1", "A2"] and data["total_price"] == 3000000 and data["prices_source"] == "server"


def test_a_standing_tier_is_confirmed_by_quantity_alone(monkeypatch):
    confirmation_engines(monkeypatch)
    # Standard cũng nằm trong sơ đồ nhưng khu không có ghế và không phải SEATED, nên là khu đứng
    result = confirm({"event_id": "ev", "tickets": [{"tier_name": "Standard", "quantity": 3}]})
    data = json.loads(result["data"])
    assert result["status"] == "waiting_for_user" and data["tickets"][0]["is_seated"] is False and data["total_price"] == 1500000


# ------------------------------------------------------------------ thời gian hiện tại trong lời nhắc
def test_system_prompt_carries_todays_date_in_vietnam_time():
    line = ba.current_time_line(datetime(2026, 12, 5, 9, 30, tzinfo=ba.VN_TZ))
    assert "Thứ Bảy" in line and "05/12/2026" in line and "09:30" in line and "search_events" in line


def test_each_request_gets_a_fresh_system_message_with_the_date(monkeypatch):
    agent, calls, _ = agent_with(monkeypatch, [fakes.FakeMessage("chào")])
    agent.chat("xin chào", [])
    system = calls[0]["messages"][0].content
    assert system.startswith("Bạn là HaloTicket AI Assistant") and "THỜI GIAN HIỆN TẠI" in system


# ------------------------------------------------------------------ không chèn ngữ cảnh tìm kiếm giữa luồng đặt vé
@pytest.mark.parametrize("message,expected", [
    ("ok lấy 2 vé hạng VIP", True),
    ("đồng ý", True),
    ("DONG Y dat luon", True),
    ("cho tôi 3 vé", True),
    ("thanh toán đi", True),
    ("tìm show cuối tuần này", False),
    ("có show nào ở Hà Nội không", False),
    ("gợi ý sự kiện khác", False),
    ("xin chào", False),
    ("", False),
])
def test_booking_continuation_detection(message, expected):
    assert ba.is_booking_continuation(message, True) is expected


def test_continuation_needs_a_selected_event():
    assert ba.is_booking_continuation("ok lấy 2 vé", False) is False


def event_lookup_rows():
    return [("e.id IN", [fakes.row(id=SLUG)])]


def test_mid_booking_message_gets_the_booking_context_not_the_search_context(monkeypatch):
    agent, calls, engines = agent_with(monkeypatch, [fakes.FakeMessage("ok")], event=event_lookup_rows())
    agent.chat("ok lấy 2 vé hạng VIP", [], selected_event_id=SLUG)
    human = calls[0]["messages"][-1].content
    assert "Khách đang đặt vé cho sự kiện" in human and SLUG in human and "KHÔNG gọi `show_event_cards`" in human
    assert "DỮ LIỆU SỰ KIỆN PHÙ HỢP" not in human and "không có sự kiện nào khớp" not in human
    # không chạy truy vấn tìm kiếm từ khóa hay "mới nhất"
    assert not any("translate(" in s.sql for s in engines.statements)


def test_a_discovery_question_still_searches_even_with_a_selected_event(monkeypatch):
    agent, calls, engines = agent_with(
        monkeypatch, [fakes.FakeMessage("ok")],
        event=event_lookup_rows() + [("translate(", [fakes.row(id="e2", title="Show B", city="HN", location_name="X")])])
    agent.chat("tìm show cuối tuần ở Hà Nội", [], selected_event_id=SLUG)
    human = calls[0]["messages"][-1].content
    assert "Khách đang đặt vé" not in human and "e2" in human


def test_an_unknown_selected_event_id_is_ignored(monkeypatch):
    agent, calls, _ = agent_with(monkeypatch, [fakes.FakeMessage("ok")], event=[("e.id IN", [])])
    agent.chat("ok lấy 2 vé", [], selected_event_id="khong-ton-tai")
    assert "Khách đang đặt vé" not in calls[0]["messages"][-1].content


# ------------------------------------------------------------------ tìm từ khóa không dấu
def test_keyword_search_ignores_accents_and_expands_city_abbreviations(monkeypatch):
    agent, calls, engines = agent_with(monkeypatch, [fakes.FakeMessage("ok")], event=[("translate(", [])])
    agent._keyword_events("ca nhac hcm")
    stmt = next(s for s in engines.statements if "translate(" in s.sql)
    assert "ILIKE" not in stmt.sql
    assert stmt.params["phrase"] == "%ca nhac hcm%"
    assert stmt.params["kw_0_0"] == "%ca%" and stmt.params["kw_1_0"] == "%nhac%"
    assert {stmt.params["kw_2_0"], stmt.params["kw_2_1"]} == {"%ho chi minh%", "%hcm%"}


def test_stop_words_are_recognised_without_accents():
    words = ba.BookingAgent._topic_words("co su kien nao gan day khong ca nhac")
    assert words == ["ca", "nhac"]


# ------------------------------------------------------------------ vòng lặp công cụ
def tool_agent(monkeypatch, replies):
    agent, calls, _ = agent_with(monkeypatch, replies, event=[("translate(", [])])
    types = FakeTool(json.dumps({"status": "success", "event_id": "ev", "showtimes": []}))
    confirm_tool = FakeTool(json.dumps({"status": "waiting_for_user", "action_type": "BOOK_TICKETS", "data": "{}"}))
    agent.dispatch["show_ticket_types"] = (types, "SHOW_TICKET_TYPES", {"success"})
    agent.dispatch["request_user_confirmation"] = (confirm_tool, "USER_CONFIRMATION_REQUIRED", {"waiting_for_user"})
    return agent, calls, types, confirm_tool


def test_a_turn_can_chain_tools_across_rounds(monkeypatch):
    replies = [
        fakes.FakeMessage("", [call("show_ticket_types", {"event_id": "ev"}, "c1")]),
        fakes.FakeMessage("", [call("request_user_confirmation", {"action_type": "BOOK_TICKETS", "data": "{}"}, "c2")]),
        fakes.FakeMessage("Mình đã tạo thẻ xác nhận cho bạn."),
    ]
    agent, calls, types, confirm_tool = tool_agent(monkeypatch, replies)
    result = agent.chat("ok chốt 2 vé VIP", [])
    assert [a["action"] for a in result["system_actions"]] == ["SHOW_TICKET_TYPES", "USER_CONFIRMATION_REQUIRED"]
    assert result["reply"] == "Mình đã tạo thẻ xác nhận cho bạn."
    assert len(types.invocations) == 1 and len(confirm_tool.invocations) == 1
    assert len(calls) == 3


def test_the_loop_stops_after_the_round_limit_and_falls_back_to_a_canned_reply(monkeypatch):
    endless = [fakes.FakeMessage("", [call("show_ticket_types", {"event_id": "ev"}, f"c{i}")]) for i in range(ba.MAX_TOOL_ROUNDS + 2)]
    agent, calls, types, _ = tool_agent(monkeypatch, endless)
    result = agent.chat("cho xem hạng vé", [])
    assert len(calls) == 1 + ba.MAX_TOOL_ROUNDS
    assert len(types.invocations) == ba.MAX_TOOL_ROUNDS
    assert result["reply"] and result["system_actions"]


def test_too_many_tool_calls_in_one_round_are_capped_but_still_answered(monkeypatch):
    many = [call("show_ticket_types", {"event_id": "ev"}, f"c{i}") for i in range(ba.MAX_TOOL_CALLS_PER_TURN + 3)]
    agent, calls, types, _ = tool_agent(monkeypatch, [fakes.FakeMessage("", many), fakes.FakeMessage("xong")])
    agent.chat("cho xem hạng vé", [])
    assert len(types.invocations) == ba.MAX_TOOL_CALLS_PER_TURN
    from langchain_core.messages import ToolMessage
    answered = {m.tool_call_id for m in calls[1]["messages"] if isinstance(m, ToolMessage)}
    assert answered == {c["id"] for c in many}  # mọi lệnh gọi đều có ToolMessage để lịch sử hợp lệ


def test_a_reply_without_tools_is_returned_as_is(monkeypatch):
    agent, calls, *_ = tool_agent(monkeypatch, [fakes.FakeMessage("Chào bạn!")])
    assert agent.chat("hello", []) == {"reply": "Chào bạn!", "system_actions": []}
    assert len(calls) == 1


# ------------------------------------------------------------------ bản gọn cho mô hình
def big_ticket_payload(showtimes=3, tickets=40):
    def ticket(i):
        return {"id": f"tt-{i}", "name": f"Hạng {i}", "price": 100000.0 * (i + 1), "description": "Quyền lợi " * 30, "status": "ACTIVE",
                "total_quantity": 100, "min_per_order": 1, "max_per_order": 6, "is_seated": i % 2 == 0,
                "area_type": "Ghế ngồi (SEATED)" if i % 2 == 0 else "Khu đứng / Tự do (STANDING)"}
    all_tickets = [ticket(i) for i in range(tickets)]
    return json.dumps({
        "status": "success", "action_type": "SHOW_TICKET_TYPES", "event_id": "ev", "event_title": "Big Show",
        "banner_url": "https://img/banner.png", "location": "HCM", "has_seatmap": True, "seatmap_image_url": "https://img/map.png",
        "tickets": all_tickets,
        "showtimes": [{"id": f"st{s}", "name": f"Suất {s}", "start_time": "19:00, 05/12/2026", "end_time": "22:00", "status": "ON_SALE",
                       "status_label": "Đang mở bán", "tickets": all_tickets} for s in range(showtimes)],
    }, ensure_ascii=False)


def test_compact_ticket_types_keep_every_tier_and_price_within_the_length_limit():
    raw = big_ticket_payload(showtimes=3, tickets=10)
    assert len(raw) > 6000  # bản đầy đủ vượt giới hạn cũ và từng bị cắt giữa chừng
    compact = ba.compact_tool_result("show_ticket_types", raw)
    assert len(compact) < 6000
    data = json.loads(compact)
    assert data["event_title"] == "Big Show" and len(data["showtimes"]) == 3 and len(data["tickets"]) == 10
    assert all(len(st["ticket_ids"]) == 10 for st in data["showtimes"])
    assert data["tickets"][0] == {"id": "tt-0", "name": "Hạng 0", "price": 100000.0, "status": "ACTIVE", "is_seated": True,
                                  "min_per_order": 1, "max_per_order": 6}
    assert "description" not in compact and "banner" not in compact and "seatmap_image_url" not in compact


def test_ticket_tiers_shared_by_every_showtime_are_listed_once():
    data = json.loads(ba.compact_tool_result("show_ticket_types", big_ticket_payload(showtimes=3, tickets=5)))
    assert [t["id"] for t in data["tickets"]] == [f"tt-{i}" for i in range(5)]
    assert data["showtimes"][1]["ticket_ids"] == [f"tt-{i}" for i in range(5)]


def test_the_model_still_sees_valid_json_after_wrapping():
    blocks = ba.DataBlocks()
    # trường hợp cực đoan: 3 suất, 40 hạng vé; bản đầy đủ gần 60 nghìn ký tự
    wrapped = blocks.wrap_json_text(ba.compact_tool_result("show_ticket_types", big_ticket_payload(3, 40)), "kết quả công cụ",
                                    max_total_chars=ba.TICKET_RESULT_MAX_CHARS)
    body = wrapped.split(chr(10), 1)[1].rsplit(chr(10), 1)[0]
    data = json.loads(body)  # không bị cắt giữa chừng nên vẫn là JSON hợp lệ
    assert data["tickets"][39]["id"] == "tt-39" and data["showtimes"][2]["ticket_ids"][39] == "tt-39"


def test_other_tools_and_error_results_are_not_changed():
    raw = json.dumps({"status": "error", "message": "x"})
    assert ba.compact_tool_result("show_ticket_types", raw) == raw
    assert ba.compact_tool_result("show_event_cards", big_ticket_payload()) == big_ticket_payload()
    assert ba.compact_tool_result("show_ticket_types", "không phải json") == "không phải json"


# ------------------------------------------------------------------ nội dung vector cũ
def test_stale_vector_content_loses_prices_and_links_before_reaching_the_model():
    text = ba.BookingAgent._text_only("Hoàng Dũng live. Giá từ 800.000đ, VIP 1,500,000 VND. Xem https://x.vn/e/1 nhé 500k")
    assert "800" not in text and "1,500" not in text and "https" not in text and "500k" not in text
    assert "Hoàng Dũng live." in text
