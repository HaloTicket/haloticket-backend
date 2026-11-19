"""Tools of the customer chatbot against fake read-only engines: adjacency, server-side prices, engine routing."""
import json
from decimal import Decimal

import pytest
from sqlalchemy.exc import OperationalError

import customer_fakes as fakes
from app.core.errors import AiError
from app.services.ai import tools


def layout(zones, seats, tiers=None):
    return json.dumps({"zones": zones, "seats": seats, "ticketTiers": tiers or [{"id": "t1", "price": 500000}]})


def seat(zone, row, number, status="available", **extra):
    return {"id": f"{zone}-{row}-{number}", "zoneId": zone, "row": row, "number": number, "status": status, **extra}


ZONES = {"z1": {"name": "VIP", "tierId": "t1"}, "z2": {"name": "Standard", "tierId": "t1"}}


def seat_engines(monkeypatch, seats, zones=ZONES, table_rows=None):
    engines = fakes.Engines(ticket=[
        ("FROM seat_layouts", [fakes.row(id="L1", draft_json=layout(zones, seats), image_url=None)]),
        ("FROM seats s", table_rows or []),
    ])
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    return engines


def find(event="ev", zone="VIP", n=2):
    return json.loads(tools.find_adjacent_seats.invoke({"event_id": event, "zone": zone, "num_seats": n}))


def test_adjacent_seats_found_in_the_same_zone_and_row(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "A", 2), seat("z1", "A", 4)])
    result = find(n=2)
    assert result["status"] == "success" and result["adjacent"] is True
    assert result["seats"] == ["A1", "A2"] and result["zone"] == "VIP" and result["row"] == "A"
    assert result["price_per_seat"] == 500000 and result["total_price"] == 1000000
    assert "sơ đồ ghế" in result["message"] and "giữ chỗ" in result["message"]


def test_only_non_adjacent_seats_is_no_adjacent_seats_never_success(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "A", 3), seat("z1", "A", 5)])
    result = find(n=2)
    assert result["status"] == "no_adjacent_seats"
    assert "seats" not in result and "seat_ids" not in result
    nearby = result["nearby_not_adjacent"]
    assert nearby["adjacent"] is False and nearby["seats"] == ["A1", "A3"]
    assert "KHÔNG liền kề" in result["message"]


def test_gap_between_rows_is_not_adjacency(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "B", 2)])
    assert find(n=2)["status"] == "no_adjacent_seats"


def test_mixed_string_and_int_numbers_never_raise_and_sort_naturally(monkeypatch):
    seats = [seat("z1", "A", "10"), seat("z1", "A", 9), seat("z1", "A", "A"), seat("z1", "A", "2"), seat("z1", "A", "B1")]
    seat_engines(monkeypatch, seats)
    result = find(n=2)
    assert result["status"] == "success" and result["seats"] == ["A9", "A10"]
    assert find(n=3)["status"] == "no_adjacent_seats"
    assert tools._natural_key("10") > tools._natural_key("9") and tools._natural_key("A1") > tools._natural_key("10")


def test_same_row_letter_in_two_zones_is_not_merged(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z2", "A", 2)])
    assert find(zone="", n=2)["status"] == "no_adjacent_seats"  # no zone given: still grouped by (zone, row)
    assert find(zone="VIP", n=1)["zone"] == "VIP"


def test_unknown_zone_is_reported_not_merged_with_all_zones(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "A", 2)])
    result = find(zone="Khu Z", n=2)
    assert result["status"] == "zone_not_found" and result["available_zones"] == ["VIP", "Standard"]


def test_seats_marked_taken_in_the_seats_table_are_excluded(monkeypatch):
    table = [fakes.row(zone_name="VIP", row_label="A", seat_number="2", seat_status="SOLD"),
             fakes.row(zone_name="VIP", row_label="A", seat_number="3", seat_status="AVAILABLE")]
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "A", 2), seat("z1", "A", 3)], table_rows=table)
    result = find(n=2)
    assert result["status"] == "no_adjacent_seats"  # A1 | A2 sold | A3
    assert find(n=1)["status"] == "success"


def test_invalid_number_of_seats_is_refused(monkeypatch):
    seat_engines(monkeypatch, [seat("z1", "A", 1)])
    assert find(n=0)["status"] == "invalid_request" and find(n=99)["status"] == "invalid_request"


def test_database_failure_is_an_error_not_a_success_result(monkeypatch):
    err = OperationalError("x", {}, Exception("boom"))
    monkeypatch.setattr(tools, "get_chatbot_engine", fakes.Engines(failure=err))
    with pytest.raises(AiError) as caught:
        tools.find_adjacent_seats.invoke({"event_id": "e", "zone": "VIP", "num_seats": 2})
    assert caught.value.code == "AI_UNAVAILABLE" and "boom" not in caught.value.message
    with pytest.raises(AiError):
        tools.show_seatmap.invoke({"event_id": "e"})


# ------------------------------------------------------------------ server-side prices
def ticket_rows():
    return [
        fakes.row(id="tt-vip", showtime_id=None, name="VIP", price=Decimal("1500000"), ticket_type_status="ACTIVE",
                  min_per_order=1, max_per_order=6, description="", total_quantity=100),
        fakes.row(id="tt-std", showtime_id=None, name="Standard", price=Decimal("500000"), ticket_type_status="ACTIVE",
                  min_per_order=1, max_per_order=10, description="", total_quantity=100),
    ]


def confirm_engines(monkeypatch):
    engines = fakes.Engines(
        event=[("FROM events e", [fakes.row(id="ev", title="Real Title")]), ("event_showtimes", [])],
        ticket=[("FROM ticket_types", ticket_rows())],
    )
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    return engines


def confirm(payload):
    return json.loads(tools.request_user_confirmation.invoke({"action_type": "BOOK_TICKETS", "data": json.dumps(payload)}))


def test_confirmation_prices_come_from_ticket_types_not_from_the_model(monkeypatch):
    confirm_engines(monkeypatch)
    result = confirm({"event_id": "ev", "event_title": "Fake", "total_price": 6000,
                      "tickets": [{"tier_name": "vip", "quantity": 2, "price": 3000},
                                  {"ticket_type_id": "tt-std", "quantity": 1, "price": 1}]})
    assert result["status"] == "waiting_for_user"
    data = json.loads(result["data"])
    assert data["event_title"] == "Real Title"
    assert [t["price"] for t in data["tickets"]] == [1500000, 500000]
    assert [t["subtotal"] for t in data["tickets"]] == [3000000, 500000]
    assert data["total_price"] == 3500000 and data["prices_source"] == "server"


@pytest.mark.parametrize("line", [
    {"tier_name": "Invented tier", "quantity": 1, "price": 1},
    {"ticket_type_id": "nope", "quantity": 1},
    {"tier_name": "VIP", "quantity": 0},
    {"tier_name": "VIP", "quantity": 7},      # above max_per_order 6
    {"tier_name": "VIP", "quantity": "two"},
])
def test_unknown_ticket_type_or_bad_quantity_is_an_error_without_invented_numbers(monkeypatch, line):
    confirm_engines(monkeypatch)
    result = confirm({"event_id": "ev", "tickets": [line], "total_price": 1})
    assert result["status"] == "error" and "data" not in result


def test_confirmation_rejects_unknown_event_and_seat_count_mismatch(monkeypatch):
    engines = confirm_engines(monkeypatch)
    assert confirm({"event_id": "ev", "tickets": [{"tier_name": "VIP", "quantity": 2}], "seats": ["A1"]})["status"] == "error"
    engines.event = [("FROM events e", [])]
    assert confirm({"event_id": "ev", "tickets": [{"tier_name": "VIP", "quantity": 1}]})["status"] == "error"


def test_confirmation_with_invalid_json_or_action_is_an_error(monkeypatch):
    confirm_engines(monkeypatch)
    assert json.loads(tools.request_user_confirmation.invoke({"action_type": "BOOK_TICKETS", "data": "{not json"}))["status"] == "error"
    assert json.loads(tools.request_user_confirmation.invoke({"action_type": "REFUND", "data": "{}"}))["status"] == "error"


# ------------------------------------------------------------------ engines used per table
def test_every_ticket_table_query_runs_on_the_ticket_engine_and_event_tables_on_the_event_engine(monkeypatch):
    seats = [seat("z1", "A", 1), seat("z1", "A", 2)]
    engines = fakes.Engines(
        event=[("FROM events e", [fakes.row(id="ev", title="T", banner_url="", thumbnail_url="", location_name="", city="",
                                            dates_summary="", min_price=1, start_time=None, end_time=None,
                                            sale_start_time=None, sale_end_time=None)]),
               ("event_showtimes", [])],
        ticket=[("GROUP BY event_id", [fakes.row(event_id="ev", min_price=Decimal("500000"))]),
                ("FROM seat_layouts", [fakes.row(id="L1", draft_json=layout(ZONES, seats), image_url=None, status="PUBLISHED", dlen=200)]),
                ("FROM seats s", []),
                ("FROM ticket_types", ticket_rows())],
    )
    monkeypatch.setattr(tools, "get_chatbot_engine", engines)
    tools.show_event_cards.invoke({"event_ids": ["ev"]})
    tools.show_ticket_types.invoke({"event_id": "ev"})
    tools.show_seatmap.invoke({"event_id": "ev"})
    find()
    confirm({"event_id": "ev", "tickets": [{"tier_name": "VIP", "quantity": 1}]})

    ticket_tables = ("ticket_types", "seat_layouts", "FROM seats", "seat_rows", "seat_zones")
    for s in engines.statements:
        mentions_ticket = any(t in s.sql for t in ticket_tables)
        mentions_event = "FROM events" in s.sql or "event_showtimes" in s.sql
        assert not (mentions_ticket and mentions_event), s.sql
        if mentions_ticket:
            assert s.kind == "ticket", s.sql
        if mentions_event:
            assert s.kind == "event", s.sql
    assert {s.kind for s in engines.statements} == {"event", "ticket"}


def test_tools_never_touch_the_shared_session(monkeypatch):
    from app.db import session

    def forbidden(*a, **k):
        raise AssertionError("shared session used by a customer tool")

    monkeypatch.setattr(session, "SessionLocal", forbidden)
    monkeypatch.setattr(session, "engine", None)
    seat_engines(monkeypatch, [seat("z1", "A", 1), seat("z1", "A", 2)])
    assert find()["status"] == "success"


def test_the_filtered_search_tool_is_registered_and_the_old_engine_aliases_are_gone():
    from app.services.ai import booking_agent as ba
    agent = ba.BookingAgent()
    assert tools.search_events in agent.tools and "search_events" in agent.dispatch
    assert not hasattr(tools, "event_engine") and not hasattr(tools, "ticket_engine")
