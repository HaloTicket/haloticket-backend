"""Exercise scope and operational aggregates through reused PostgreSQL sessions."""
import os
from datetime import datetime, timedelta

import pytest
from sqlalchemy import text
from sqlalchemy.orm import sessionmaker

from app.core.config import settings
from app.db.session import make_engine
from app.services.ai import organizer_agent, organizer_data, scope

pytestmark = [
    pytest.mark.db,
    pytest.mark.skipif(os.getenv("RUN_DB_TESTS") != "1", reason="set RUN_DB_TESTS=1 and DATABASE_URL"),
]

NOW = datetime(2026, 11, 23, 10)


@pytest.fixture
def databases(monkeypatch):
    engines = {name: make_engine(settings.database_url, pool_size=1, max_overflow=0)
               for name in ("EVENT", "BOOKING", "TICKET")}
    monkeypatch.setattr(scope, "SessionLocal", sessionmaker(bind=engines["EVENT"]))
    monkeypatch.setattr(organizer_data, "_database", engines.__getitem__)
    with engines["EVENT"].begin() as conn:
        conn.execute(text("""
            CREATE TEMP TABLE staff_assignments (event_id text, user_id text, status text, role text);
            CREATE TEMP TABLE events (id text PRIMARY KEY, title text, event_status text, start_time timestamp, end_time timestamp);
            CREATE TEMP TABLE event_showtimes (id text PRIMARY KEY, event_id text, name text, start_time timestamp,
                end_time timestamp, sale_start_time timestamp, sale_end_time timestamp);
            INSERT INTO events VALUES
                ('A','For Alice','LIVE','2026-11-26','2026-11-26 21:00'),
                ('B','For Both','LIVE','2026-11-27','2026-11-27 21:00'),
                ('C','Private third party','LIVE','2026-11-28','2026-11-28 21:00');
            INSERT INTO staff_assignments VALUES
                ('A','alice','ACTIVE','OWNER'), ('B','alice','ACTIVE','MANAGER'),
                ('C','alice','REVOKED','OWNER'), ('A','bob','ACTIVE','ADMIN'), ('A','scanner','ACTIVE','CHECKIN_STAFF');
            INSERT INTO event_showtimes VALUES
                ('S1','A','Evening','2026-11-26 19:00','2026-11-26 21:00',NULL,NULL),
                ('S2','A','Morning','2026-11-26 09:00','2026-11-26 11:00',NULL,NULL),
                ('S3','B','Afternoon','2026-11-27 14:00','2026-11-27 16:00',NULL,NULL),
                ('S4','C','Private','2026-11-28 19:00','2026-11-28 21:00',NULL,NULL);
            INSERT INTO events SELECT 'PAGE-' || i, 'Other event', 'DRAFT', NULL, NULL FROM generate_series(1, 105) i;
            INSERT INTO staff_assignments
                 SELECT 'PAGE-' || i, 'paged', 'ACTIVE', 'MANAGER' FROM generate_series(1, 105) i;
        """))
    with engines["BOOKING"].begin() as conn:
        conn.execute(text("""
            CREATE TEMP TABLE bookings (id text PRIMARY KEY,event_id text,status text,created_at timestamp,updated_at timestamp);
            CREATE TEMP TABLE booking_items (booking_id text,quantity integer);
            INSERT INTO bookings VALUES
                ('BA','A','PAID','2026-11-20','2026-11-20'),
                ('BA-NO-DENOMINATOR','A','PAID','2026-11-20','2026-11-20'),
                ('BB','B','PAID','2026-11-21','2026-11-21'),
                ('BC','C','PAID','2026-11-22','2026-11-22');
            INSERT INTO booking_items VALUES ('BA',2), ('BB',1), ('BC',10);
        """))
    with engines["TICKET"].begin() as conn:
        conn.execute(text("""
            CREATE TEMP TABLE ticket_types (id text PRIMARY KEY,event_id text,showtime_id text,name text,
                ticket_type_status text,total_quantity integer,reserved_quantity integer,initial_quantity integer,price numeric);
            CREATE TEMP TABLE tickets (event_id text,showtime_id text,ticket_type_id text,booking_id text,
                status text,issued_at timestamp,created_at timestamp,used_at timestamp,acquisition_type text,
                purchase_price numeric,issue_key text);
            INSERT INTO ticket_types VALUES
                ('T1','A','S1','VIP','ACTIVE',2,1,40,200000),
                ('T2','A','S2','General','ACTIVE',20,0,20,100000),
                ('T3','B','S3','Regular','ACTIVE',12,0,20,777000),
                ('T4','C','S4','Private price','ACTIVE',99,0,100,999999);
            INSERT INTO tickets VALUES
                ('A','S1','T1','BA','USED','2026-11-20','2026-11-20','2026-11-22','PRIMARY',200000,'BA:T1:1'),
                ('B','S3','T3','BB','VALID','2026-11-21','2026-11-21',NULL,'PRIMARY',777000,NULL),
                ('C','S4','T4','BC','VALID','2026-11-22','2026-11-22',NULL,'PRIMARY',999999,NULL);
        """))
    yield engines
    for engine in engines.values():
        engine.dispose()


def test_scope_revalidates_over_the_same_pool_and_reads_the_last_page(databases):
    alice = scope.OrganizerChatIdentity(False, "alice")
    bob = scope.OrganizerChatIdentity(False, "bob")
    assert [(e["event_id"], e["can_view_finance"]) for e in scope.chat_events(alice, None)] == [("A", True), ("B", False)]
    assert [e["event_id"] for e in scope.chat_events(bob, None)] == ["A"]
    assert scope.chat_events(scope.OrganizerChatIdentity(False, "scanner"), None) == []
    assert len(scope.chat_events(scope.OrganizerChatIdentity(False, "paged"), None)) == 105
    assert [e["event_id"] for e in scope.chat_events(scope.OrganizerChatIdentity(True), "C")] == ["C"]
    with databases["EVENT"].begin() as conn:
        conn.execute(text("UPDATE staff_assignments SET status='REVOKED' WHERE user_id='alice' AND event_id='A'"))
    assert scope.chat_events(alice, "A") == []
    assert [e["event_id"] for e in scope.chat_events(alice, None)] == ["B"]


def test_ticket_and_booking_aggregates_distinguish_zero_missing_and_restricted_finance(databases, monkeypatch):
    alice = scope.OrganizerChatIdentity(False, "alice")
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    agent = organizer_agent.organizer_agent
    managed = agent.chat(message="Compare ticket sales and revenue", history=[], events=scope.chat_events(alice, None),
                         event_id=None, actor="alice", locale="en")
    assert {card["event_id"] for card in managed["cards"]} == {"A", "B"}
    b = next(card for card in managed["cards"] if card["event_id"] == "B")
    assert not any(metric["key"] == "ticket_sales_revenue" for metric in b["metrics"])
    bob = scope.OrganizerChatIdentity(False, "bob")
    bob_overview = agent.chat(message="Overview", history=[], events=scope.chat_events(bob, None),
                             event_id=None, actor="bob", locale="en")
    assert len(bob_overview["cards"]) == 1
    assert not any(metric["key"] == "ticket_sales_revenue" for metric in bob_overview["totals"])
    detail = agent.chat(message="How many tickets left for the evening show, any paid orders without tickets?",
                        history=[], events=scope.chat_events(alice, "A"),
                        event_id="A", actor="alice", locale="en")
    assert "Evening: 1 ticket available" in detail["reply"]
    assert "at least 1 paid order" in detail["reply"]
    sales = [m for m in detail["cards"][0]["metrics"] if m["key"] == "ticket_sales_revenue"]
    assert [m["value"] for m in sales] == [200000, 0]
    checkins = [m["value"] for m in detail["cards"][0]["metrics"] if m["key"] == "check_ins"]
    assert checkins == [1, 0]
    detail_sales = [m for m in detail["totals"] if m["key"] == "ticket_sales_revenue"]
    assert [m["value"] for m in detail_sales] == [200000, 0]
    assert {card["showtime_id"] for card in detail["cards"]} == {None, "S1", "S2"}
    mismatch = next(warning for warning in detail["warnings"] if warning["code"] == "paid_order_missing_tickets")
    assert mismatch["metrics"][0]["value"] == 1
    assert mismatch["metrics"][0]["completeness"] == "partial"
    assert any(warning["code"] == "low_stock" for warning in detail["warnings"])
    assert "Private third party" not in str(detail)


def test_gift_and_resale_keep_the_original_issuance_without_using_the_resale_price(databases, monkeypatch):
    with databases["TICKET"].begin() as conn:
        conn.execute(text("""
            UPDATE tickets SET acquisition_type='GIFT' WHERE booking_id='BA';
            INSERT INTO tickets VALUES
                ('A','S1','T1','MARKET-ORDER','VALID','2026-11-20','2026-11-20',NULL,'RESALE',600000,'BA:T1:2');
        """))
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), "A")
    result = organizer_agent.organizer_agent.chat(message="Ticket sales", history=[], events=events,
                                                 event_id="A", actor="alice", locale="en")
    first = result["cards"][0]["metrics"]
    assert next(m for m in first if m["key"] == "tickets_sold")["value"] == 2
    assert next(m for m in first if m["key"] == "ticket_sales_revenue")["value"] is None
    assert not any(warning["code"] == "paid_order_missing_tickets" for warning in result["warnings"])


def test_event_lookup_filters_scheduled_dates_without_invoking_the_advisor(databases, monkeypatch):
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), None)
    agent = organizer_agent.organizer_agent
    result = agent.chat(message="Sự kiện nào diễn ra ngày 27/11/2026?", history=[], events=events,
                        event_id=None, actor="alice", locale="vi")
    assert [card["event_id"] for card in result["cards"]] == ["B"]
    no_drafts = agent.chat(message="List draft events", history=[], events=events,
                          event_id=None, actor="alice", locale="en")
    assert no_drafts["status"] == "empty"


def test_weekly_comparison_handles_zero_previous_and_named_followup(databases, monkeypatch):
    now = datetime(2026, 11, 25, 10, tzinfo=organizer_agent._LOCAL_TIME)
    monkeypatch.setattr(organizer_agent, "_today", lambda: now)
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), None)
    agent = organizer_agent.organizer_agent
    result = agent.chat(message="So sánh số vé tuần này và tuần trước", history=[], events=events,
                        event_id=None, actor="alice", locale="vi")
    assert "Tuần này đến hiện tại: 0 vé" in result["reply"]
    assert "Tuần trước cùng thời điểm: 0 vé" in result["reply"]
    assert "%" not in result["reply"]
    detail = agent.chat(message="Sự kiện này thì sao?", history=[
        {"role": "user", "content": 'Xem "For Alice"'},
    ], events=events, event_id=None, actor="alice", locale="vi")
    assert {card["event_id"] for card in detail["cards"]} == {"A"}
    tier = next(card for card in detail["cards"] if card["ticket_type_id"] == "T1")
    assert any(metric["key"] == "listed_price" and metric["value"] == 200000 for metric in tier["metrics"])


@pytest.mark.parametrize(("message", "start", "end"), [
    ("Doanh thu tháng trước", "2026-10-01T00:00:00+07:00", "2026-11-01T00:00:00+07:00"),
    ("Doanh thu từ 1/11 đến 15/11", "2026-11-01T00:00:00+07:00", "2026-11-16T00:00:00+07:00"),
    ("Doanh thu từ 1/11 đến 15/11/2025", "2025-11-01T00:00:00+07:00", "2025-11-16T00:00:00+07:00"),
])
def test_analytics_uses_requested_calendar_dates(databases, monkeypatch, message, start, end):
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), None)
    result = organizer_agent.organizer_agent.chat(message=message, history=[], events=events,
                                                 event_id=None, actor="alice", locale="vi")
    assert result["totals"][0]["period"] == {"from": start, "to": end}
    # A finance-restricted aggregate cannot offer a daily revenue query.
    assert result["follow_up"] is None


def test_analytics_asks_for_valid_dates_instead_of_displaying_the_default_period(monkeypatch):
    def unexpected_query(*args, **kwargs):
        pytest.fail("The assistant queried metrics before resolving an invalid date range")

    monkeypatch.setattr(organizer_agent, "read_event_metrics", unexpected_query)
    result = organizer_agent.organizer_agent.chat(message="Doanh thu từ 31/11 đến 15/12", history=[],
                                                 events=[{"event_id": "A"}], event_id="A", actor="alice", locale="vi")
    assert "ngày hợp lệ" in result["reply"]
    assert not result.get("totals")


def test_unqueried_reconciliation_is_missing(databases, monkeypatch):
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), None)
    result = organizer_agent.organizer_agent.chat(message="Doanh thu", history=[], events=events,
                                                 event_id=None, actor="alice", locale="vi")
    metric = next(m for m in result["cards"][0]["metrics"] if m["key"] == "paid_orders_missing_tickets")
    assert metric["value"] is None and metric["completeness"] == "missing"


def test_daily_trend_uses_real_dated_sales_and_respects_finance_scope(databases, monkeypatch):
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    alice = scope.OrganizerChatIdentity(False, "alice")
    agent = organizer_agent.organizer_agent
    event_trend = agent.chat(message="Doanh thu theo ngày từ 19/11 đến 21/11", history=[],
                              events=scope.chat_events(alice, "A"), event_id="A", actor="alice", locale="vi")
    assert event_trend["actions"][0]["database_data"] == [
        {"date": "2026-11-19", "ticket_sales_revenue": 0},
        {"date": "2026-11-20", "ticket_sales_revenue": 200000},
        {"date": "2026-11-21", "ticket_sales_revenue": 0},
    ]
    managed_trend = agent.chat(message="Số vé theo ngày từ 19/11 đến 21/11", history=[],
                                events=scope.chat_events(alice, None), event_id=None, actor="alice", locale="vi")
    assert managed_trend["actions"][0]["database_data"] == [
        {"date": "2026-11-19", "tickets_sold": 0},
        {"date": "2026-11-20", "tickets_sold": 1},
        {"date": "2026-11-21", "tickets_sold": 1},
    ]
    restricted_trend = agent.chat(message="Doanh thu theo ngày từ 19/11 đến 21/11", history=[],
                                   events=scope.chat_events(alice, None), event_id=None, actor="alice", locale="vi")
    assert restricted_trend["actions"] == []

    event = scope.chat_events(alice, "A")
    for question, key, counts in (
        ("Lượt check-in theo ngày", "check_ins", [0, 0, 0, 1]),
        ("Đơn đã thanh toán theo ngày", "paid_orders", [0, 2, 0, 0]),
    ):
        breakdown = agent.chat(message=f"{question} từ 19/11 đến 22/11", history=[],
                               events=event, event_id="A", actor="alice", locale="vi")
        assert [row[key] for row in breakdown["actions"][0]["database_data"]] == counts


def test_summary_answers_all_requested_metrics_and_short_question_stays_short(databases, monkeypatch):
    monkeypatch.setattr(organizer_agent, "_today", lambda: NOW.replace(tzinfo=organizer_agent._LOCAL_TIME))
    events = scope.chat_events(scope.OrganizerChatIdentity(False, "alice"), None)
    agent = organizer_agent.organizer_agent
    kwargs = dict(history=[], events=events, event_id=None, actor="alice", locale="vi")
    summary = agent.chat(
        message="Tổng hợp doanh thu, số vé đã bán và đơn thanh toán của tất cả sự kiện tôi quản lý", **kwargs
    )
    assert summary["show_analytics"] is True
    assert "200.000" in summary["reply"]
    assert "**2** vé đã bán" in summary["reply"]
    assert "\n- **3** đơn đã thanh toán" in summary["reply"]
    short = agent.chat(message="đã bán được bao nhiêu vé", **kwargs)
    assert short["show_analytics"] is False
    assert "**2** vé đã bán" in short["reply"]
    assert "VND" not in short["reply"]
    assert short["follow_up"] is None
