"""Fixed, read-only queries for the organizer chatbot's scoped aggregates."""
import os
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from functools import lru_cache

from sqlalchemy import Text, bindparam, text

from app.core.config import settings
from app.core.errors import AI_DB_NOT_CONFIGURED, ai_error
from app.db.session import make_engine

PAGE_SIZE = 100


@lru_cache(maxsize=3)
def _engine(url):
    return make_engine(url)


def _database(source):
    event_url = os.getenv("EVENT_DB_URL") or settings.database_url
    url = os.getenv(source + "_DB_URL")
    if not url and event_url:
        from sqlalchemy.engine import make_url
        base = make_url(event_url)
        url = base if source == "EVENT" else base.set(database=source.lower() + "_db")
    if not url:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    return _engine(url)


def _read_only(conn):
    conn.execute(text("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"))
    conn.execute(text("SELECT set_config('statement_timeout', :ms, true)"), {"ms": str(settings.ai_sql_timeout_ms)})


def _rows(conn, sql, params, *lists):
    query = text(sql).bindparams(*(bindparam(name, expanding=True, type_=Text()) for name in lists))
    return conn.execute(query, params).mappings().all()


_SHOWTIMES = """
    SELECT event_id, id AS showtime_id, name, start_time, end_time, sale_start_time, sale_end_time
      FROM event_showtimes WHERE event_id IN :ids ORDER BY start_time, id
"""

_TICKET_TYPES = """
    SELECT event_id, showtime_id, id AS ticket_type_id, name, ticket_type_status AS status,
           CASE WHEN total_quantity IS NOT NULL
                THEN greatest(0, total_quantity - coalesce(reserved_quantity, 0)) END AS remaining,
           initial_quantity AS capacity, price
      FROM ticket_types WHERE event_id IN :ids
     ORDER BY showtime_id, id
"""

_TICKET_FACTS = """
        SELECT t.event_id, coalesce(t.showtime_id, tt.showtime_id) AS showtime_id, t.ticket_type_id,
               t.status, coalesce(t.issued_at, t.created_at) AS sold_at, t.used_at,
               (coalesce(t.acquisition_type, 'PRIMARY') NOT IN ('PRIMARY', 'GIFT')
                 AND coalesce(position(':' in t.issue_key), 0) = 0) AS unlinked_issuance,
               CASE WHEN t.event_id IN :finance_ids AND (
                   coalesce(t.acquisition_type, 'PRIMARY') = 'PRIMARY'
                   OR (t.acquisition_type = 'GIFT' AND
                       (t.issue_key IS NULL OR split_part(t.issue_key, ':', 1) = t.booking_id))
               ) THEN t.purchase_price END AS revenue
          FROM tickets t
          JOIN ticket_types tt ON tt.id = t.ticket_type_id AND tt.event_id = t.event_id
         WHERE t.event_id IN :ids AND t.status <> 'REVOKED'
"""

_TICKETS = f"""
    WITH facts AS ({_TICKET_FACTS}), counts AS (
        SELECT event_id, showtime_id, ticket_type_id,
               count(*) FILTER (WHERE sold_at >= :start AND sold_at < :end) AS sold,
               count(*) FILTER (WHERE sold_at >= :previous AND sold_at < :previous_end) AS previous_sold,
               count(*) FILTER (WHERE sold_at IS NULL) AS undated_sales,
               count(*) FILTER (WHERE unlinked_issuance) AS unlinked_issuances,
               count(*) FILTER (WHERE status IN ('VALID','USED','LOCKED_FOR_RESALE','LOCKED_FOR_TRANSFER')) AS eligible,
               count(*) FILTER (WHERE status = 'USED') AS checked_in_total,
               count(*) FILTER (WHERE status = 'USED' AND used_at >= :start AND used_at < :end) AS checked_in,
               count(*) FILTER (WHERE status = 'USED' AND used_at >= :previous AND used_at < :previous_end) AS previous_checked_in,
               count(*) FILTER (WHERE status = 'USED' AND used_at IS NULL) AS undated_checkins,
               sum(revenue) FILTER (WHERE sold_at >= :start AND sold_at < :end) AS sales_revenue,
               count(revenue) FILTER (WHERE sold_at >= :start AND sold_at < :end) AS priced_sales,
               sum(revenue) FILTER (WHERE sold_at >= :previous AND sold_at < :previous_end) AS previous_sales_revenue,
               count(revenue) FILTER (WHERE sold_at >= :previous AND sold_at < :previous_end) AS previous_priced_sales
          FROM facts GROUP BY event_id, showtime_id, ticket_type_id
    ) SELECT * FROM counts
"""

_DAILY_TICKET_QUERIES = {
    key: f"""
        WITH facts AS ({_TICKET_FACTS})
        SELECT event_id, CAST({date_column} AS date) AS day, {aggregate} AS value
          FROM facts WHERE {date_column} >= :start AND {date_column} < :end {condition}
         GROUP BY event_id, CAST({date_column} AS date)
    """
    for key, date_column, aggregate, condition in (
        ("tickets_sold", "sold_at", "count(*)", ""),
        ("ticket_sales_revenue", "sold_at", "sum(revenue)", ""),
        ("check_ins", "used_at", "count(*)", "AND status = 'USED'"),
    )
}

_BOOKINGS = """
    SELECT event_id, status,
           count(*) FILTER (WHERE recorded_at >= :start AND recorded_at < :end) AS current_count,
           count(*) FILTER (WHERE recorded_at >= :previous AND recorded_at < :previous_end) AS previous_count
      FROM (SELECT event_id, status, CASE WHEN status IN ('PAID', 'CONFIRMED') THEN updated_at
                                          ELSE created_at END AS recorded_at
              FROM bookings WHERE event_id IN :ids) b
     WHERE recorded_at >= :previous AND recorded_at < :end
     GROUP BY event_id, status
"""

_DAILY_PAID_ORDERS = """
    SELECT event_id, CAST(updated_at AS date) AS day, count(*) AS value
      FROM bookings WHERE event_id IN :ids AND status IN ('PAID', 'CONFIRMED')
       AND updated_at >= :start AND updated_at < :end
     GROUP BY event_id, CAST(updated_at AS date)
"""

_PAID_ORDERS = """
    SELECT b.event_id, b.id AS booking_id,
           CASE WHEN count(i.quantity) = count(*) THEN sum(i.quantity) END AS expected
      FROM bookings b LEFT JOIN booking_items i ON i.booking_id = b.id
     WHERE b.event_id IN :ids AND b.status IN ('PAID', 'CONFIRMED')
       AND b.updated_at >= :lookback AND b.updated_at < :grace
       AND (CAST(:after AS text) IS NULL OR b.id > :after)
     GROUP BY b.event_id, b.id ORDER BY b.id LIMIT :page_size
"""

_ISSUED = """
    SELECT event_id, original_booking_id AS booking_id, count(*) AS issued
      FROM (
          SELECT event_id,
                 CASE WHEN position(':' in issue_key) > 0 THEN split_part(issue_key, ':', 1)
                      WHEN coalesce(acquisition_type, 'PRIMARY') IN ('PRIMARY', 'GIFT') THEN booking_id
                 END AS original_booking_id
            FROM tickets
           WHERE event_id IN :ids AND status <> 'REVOKED'
      ) t
     WHERE original_booking_id IN :bookings
     GROUP BY event_id, original_booking_id
"""


def _missing_tickets(booking_conn, ticket_conn, ids, now):
    discrepancies = defaultdict(Counter)
    params = {
        "ids": ids,
        "lookback": now - timedelta(days=7),
        "grace": now - timedelta(minutes=settings.attn_paid_without_tickets_minutes),
        "after": None,
        "page_size": PAGE_SIZE,
    }
    while True:
        paid = _rows(booking_conn, _PAID_ORDERS, params, "ids")
        if not paid:
            return discrepancies
        issued = _rows(ticket_conn, _ISSUED, {"ids": ids, "bookings": [row["booking_id"] for row in paid]},
                       "ids", "bookings")
        issued_counts = {(row["event_id"], row["booking_id"]): row["issued"] for row in issued}
        for row in paid:
            event_id, booking_id, expected = row["event_id"], row["booking_id"], row["expected"]
            present = issued_counts.get((event_id, booking_id), 0)
            if expected is None or expected <= 0:
                discrepancies[event_id]["unknown_orders"] += 1
            elif present < expected:
                discrepancies[event_id].update(orders=1, missing=expected - present)
        if len(paid) < PAGE_SIZE:
            return discrepancies
        params["after"] = paid[-1]["booking_id"]


def read_event_metrics(events: list[dict], previous: datetime, start: datetime, end: datetime, now: datetime,
                       *, previous_end: datetime | None = None, operations: bool = True,
                       daily_metric: str | None = None) -> dict:
    """Each query restricts rows to authorized events before grouping. Raw orders stay in this layer."""
    results = {event["event_id"]: defaultdict(list) for event in events}
    with (_database("EVENT").connect() as event_conn, _database("TICKET").connect() as ticket_conn,
          _database("BOOKING").connect() as booking_conn):
        for conn in (event_conn, ticket_conn, booking_conn):
            _read_only(conn)
        for offset in range(0, len(events), PAGE_SIZE):
            page = events[offset:offset + PAGE_SIZE]
            ids = [event["event_id"] for event in page]
            params = {"ids": ids, "finance_ids": [event["event_id"] for event in page if event["can_view_finance"]],
                      "previous": previous, "previous_end": previous_end or start, "start": start, "end": end}
            groups = [
                ("tickets", _rows(ticket_conn, _TICKETS, params, "ids", "finance_ids")),
                ("bookings", _rows(booking_conn, _BOOKINGS, params, "ids")),
            ]
            if daily_metric is not None and start < min(end, now):
                daily_params = {**params, "end": min(end, now)}
                if daily_metric == "paid_orders":
                    daily_rows = _rows(booking_conn, _DAILY_PAID_ORDERS, daily_params, "ids")
                else:
                    daily_rows = _rows(ticket_conn, _DAILY_TICKET_QUERIES[daily_metric], daily_params,
                                       "ids", "finance_ids")
                groups.append(("daily", daily_rows))
            if operations:
                groups.extend([
                    ("showtimes", _rows(event_conn, _SHOWTIMES, params, "ids")),
                    ("tiers", _rows(ticket_conn, _TICKET_TYPES, params, "ids")),
                ])
            for name, rows in groups:
                for row in rows:
                    results[row["event_id"]][name].append(row)
            if operations:
                discrepancies = _missing_tickets(booking_conn, ticket_conn, ids, now)
                for event_id in ids:
                    results[event_id]["missing_tickets"] = discrepancies.get(event_id, {})
    return results
