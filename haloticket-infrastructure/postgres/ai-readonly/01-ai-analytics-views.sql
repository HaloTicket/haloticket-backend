-- Analysis views for the AI assistant. Run on event_db (the database ai-assistant connects to), as the database owner:
--
--   psql -U <owner> -d event_db -v ON_ERROR_STOP=1 -f 01-ai-analytics-views.sql
--
-- NOT run by the application and NOT run automatically. Read it, then run it once, then run 02-ai-readonly-role.sql.
-- Safe to run again (CREATE OR REPLACE / IF NOT EXISTS).
--
-- What these views are for: the data-analysis feature lets a language model write SQL. It may only read these views.
-- They hold event, ticket-type, price, stock and sales figures; they do NOT hold buyer names, e-mail addresses, phone
-- numbers, ID numbers, QR codes, IP addresses, voucher codes or payout data. Every view and column has a comment; the
-- comments are what the model is told about the data.
--
-- The views are owned by the role that creates them and run with its privileges, so the read-only role needs no access
-- to the tables underneath. Views over ticket_types, tickets and bookings are created only when those tables exist in
-- event_db (they are postgres_fdw foreign tables of ticket_db and booking_db on the current setup).

CREATE SCHEMA IF NOT EXISTS ai_analytics;
COMMENT ON SCHEMA ai_analytics IS 'Views for AI data analysis: aggregated or public event data, no personal data.';

-- ---------------------------------------------------------------------------------------------------------------------
-- Always created: they read local event_db tables
-- ---------------------------------------------------------------------------------------------------------------------

CREATE OR REPLACE VIEW ai_analytics.v_events AS
SELECT e.id                      AS event_id,
       e.title                   AS title,
       c.name                    AS category_name,
       e.location_name           AS location_name,
       e.city                    AS city,
       e.ward                    AS ward,
       e.address                 AS address,
       e.dates_summary           AS dates_summary,
       e.start_time              AS start_time,
       e.end_time                AS end_time,
       e.sale_start_time         AS sale_start_time,
       e.sale_end_time           AS sale_end_time,
       e.event_status            AS event_status,
       e.event_approval_status   AS approval_status,
       e.min_price               AS min_price,
       e.is_private              AS is_private,
       (SELECT string_agg(DISTINCT o.name, ', ')
          FROM event_organizer_mapping m
          JOIN event_organizers o ON o.id = m.organizer_id
         WHERE m.event_id = e.id) AS organizer_name,
       e.created_at              AS created_at
  FROM events e
  LEFT JOIN event_categories c ON c.id = e.category_id;

COMMENT ON VIEW ai_analytics.v_events IS 'One row per event: public information about the event. event_status is DRAFT, APPROVED or LIVE; approval_status is DRAFT, PENDING, APPROVED or REJECTED. Times are local Vietnam time without time zone.';
COMMENT ON COLUMN ai_analytics.v_events.event_id IS 'Event id (join key for the other views).';
COMMENT ON COLUMN ai_analytics.v_events.title IS 'Event name.';
COMMENT ON COLUMN ai_analytics.v_events.category_name IS 'Category, for example music, sport, workshop.';
COMMENT ON COLUMN ai_analytics.v_events.city IS 'City where the event takes place.';
COMMENT ON COLUMN ai_analytics.v_events.start_time IS 'When the event starts.';
COMMENT ON COLUMN ai_analytics.v_events.end_time IS 'When the event ends.';
COMMENT ON COLUMN ai_analytics.v_events.sale_start_time IS 'When ticket sales open.';
COMMENT ON COLUMN ai_analytics.v_events.sale_end_time IS 'When ticket sales close.';
COMMENT ON COLUMN ai_analytics.v_events.min_price IS 'Lowest ticket price in VND.';
COMMENT ON COLUMN ai_analytics.v_events.is_private IS 'true when the event is not listed publicly.';
COMMENT ON COLUMN ai_analytics.v_events.organizer_name IS 'Public name of the organizer (brand), not a person.';

CREATE OR REPLACE VIEW ai_analytics.v_event_showtimes AS
SELECT s.event_id        AS event_id,
       s.id              AS showtime_id,
       s.name            AS showtime_name,
       s.start_time      AS start_time,
       s.end_time        AS end_time,
       s.sale_start_time AS sale_start_time,
       s.sale_end_time   AS sale_end_time
  FROM event_showtimes s;

COMMENT ON VIEW ai_analytics.v_event_showtimes IS 'One row per showtime (session) of an event. An event may have several showtimes.';
COMMENT ON COLUMN ai_analytics.v_event_showtimes.event_id IS 'Event id; joins v_events.event_id.';
COMMENT ON COLUMN ai_analytics.v_event_showtimes.showtime_id IS 'Showtime id; joins v_ticket_types.showtime_id.';

CREATE OR REPLACE VIEW ai_analytics.v_event_views_daily AS
SELECT l.event_id                      AS event_id,
       l.viewed_at::date               AS view_date,
       count(*)                        AS page_views,
       count(DISTINCT l.session_id)    AS unique_sessions
  FROM event_view_logs l
 GROUP BY l.event_id, l.viewed_at::date;

COMMENT ON VIEW ai_analytics.v_event_views_daily IS 'Visits of the event page per event and day. Counts only: no visitor identity.';
COMMENT ON COLUMN ai_analytics.v_event_views_daily.page_views IS 'Number of page views that day.';
COMMENT ON COLUMN ai_analytics.v_event_views_daily.unique_sessions IS 'Number of distinct browser sessions that day.';

CREATE OR REPLACE VIEW ai_analytics.v_vouchers AS
SELECT v.event_id          AS event_id,
       v.title             AS voucher_title,
       v.discount_type     AS discount_type,
       v.discount_value    AS discount_value,
       v.max_discount_amount AS max_discount_amount,
       v.min_order_amount  AS min_order_amount,
       v.usage_limit       AS usage_limit,
       v.used_count        AS used_count,
       v.status            AS status,
       v.start_at          AS start_at,
       v.end_at            AS end_at,
       v.apply_scope       AS apply_scope
  FROM vouchers v;

COMMENT ON VIEW ai_analytics.v_vouchers IS 'Vouchers of an event, without the voucher code. used_count / usage_limit shows how much of a voucher was used.';
COMMENT ON COLUMN ai_analytics.v_vouchers.discount_type IS 'PERCENTAGE or a fixed amount.';
COMMENT ON COLUMN ai_analytics.v_vouchers.discount_value IS 'Percent or VND, depending on discount_type.';

CREATE OR REPLACE VIEW ai_analytics.v_venues AS
SELECT v.id        AS venue_id,
       v.name      AS name,
       v.city      AS city,
       v.ward      AS ward,
       v.address   AS address,
       v.capacity  AS capacity,
       v.type      AS venue_type,
       v.verified  AS verified,
       v.is_public AS is_public
  FROM venues v;

COMMENT ON VIEW ai_analytics.v_venues IS 'Venues (places where events are held) with their capacity.';

CREATE OR REPLACE VIEW ai_analytics.v_event_categories AS
SELECT c.id AS category_id, c.name AS name, c.status AS status, c.active AS active
  FROM event_categories c;

COMMENT ON VIEW ai_analytics.v_event_categories IS 'Event categories.';

CREATE OR REPLACE VIEW ai_analytics.v_search_terms AS
SELECT s.query_term AS query_term, s.search_count AS search_count, s.created_at AS first_searched_at
  FROM search_logs s;

COMMENT ON VIEW ai_analytics.v_search_terms IS 'What visitors typed in the search box and how many times. Anonymous counts.';

-- ---------------------------------------------------------------------------------------------------------------------
-- Created only when the foreign tables of ticket_db / booking_db exist in event_db
-- ---------------------------------------------------------------------------------------------------------------------

DO $views$
BEGIN
  IF to_regclass('public.ticket_types') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_ticket_types AS
      SELECT t.id                AS ticket_type_id,
             t.event_id          AS event_id,
             t.showtime_id       AS showtime_id,
             t.name              AS name,
             t.price             AS price,
             t.initial_quantity  AS quantity_initial,
             t.total_quantity    AS quantity_remaining,
             t.ticket_type_status AS status,
             t.is_free           AS is_free,
             t.min_per_order     AS min_per_order,
             t.max_per_order     AS max_per_order
        FROM ticket_types t
    $v$;
    EXECUTE $v$COMMENT ON VIEW ai_analytics.v_ticket_types IS 'Ticket types (classes such as VIP, Standard) of an event or showtime, with price and stock.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_types.price IS 'Ticket price in VND.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_types.quantity_initial IS 'Tickets put on sale in total.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_types.quantity_remaining IS 'Tickets still available.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_types.status IS 'ACTIVE, SOLD_OUT or HIDDEN.'$v$;
  END IF;

  IF to_regclass('public.tickets') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_ticket_sales_daily AS
      SELECT t.event_id                          AS event_id,
             t.ticket_type_id                    AS ticket_type_id,
             COALESCE(t.issued_at, t.created_at)::date AS sale_date,
             count(*)                            AS tickets_sold,
             count(*) FILTER (WHERE t.status = 'USED') AS tickets_checked_in,
             COALESCE(sum(t.purchase_price), 0)  AS revenue
        FROM tickets t
       WHERE t.status <> 'REVOKED'
         AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY')
       GROUP BY t.event_id, t.ticket_type_id, COALESCE(t.issued_at, t.created_at)::date
    $v$;
    EXECUTE $v$COMMENT ON VIEW ai_analytics.v_ticket_sales_daily IS 'Tickets sold per event, ticket type and day (first sale only, not resale or gifts), with the revenue in VND. No buyer information.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_sales_daily.tickets_sold IS 'Tickets issued that day.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_sales_daily.tickets_checked_in IS 'Of those, tickets already scanned at the gate.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_ticket_sales_daily.revenue IS 'Sum of the purchase price in VND.'$v$;
  END IF;

  IF to_regclass('public.bookings') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_booking_summary_daily AS
      SELECT b.event_id               AS event_id,
             b.created_at::date       AS booking_date,
             b.status                 AS status,
             count(*)                 AS bookings_count,
             COALESCE(sum(b.total_amount), 0)    AS total_amount,
             COALESCE(sum(b.discount_amount), 0) AS discount_amount
        FROM bookings b
       GROUP BY b.event_id, b.created_at::date, b.status
    $v$;
    EXECUTE $v$COMMENT ON VIEW ai_analytics.v_booking_summary_daily IS 'Orders per event, day and status, with the amounts in VND. Revenue is the total_amount of orders with status PAID. No buyer information.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_booking_summary_daily.status IS 'PENDING, PAID, CANCELLED, EXPIRED or REFUNDED.'$v$;
    EXECUTE $v$COMMENT ON COLUMN ai_analytics.v_booking_summary_daily.total_amount IS 'Sum of the order totals in VND.'$v$;
  END IF;
END
$views$;

-- ---------------------------------------------------------------------------------------------------------------------
-- VERIFY (read only): the views that exist now and how many columns each has
-- ---------------------------------------------------------------------------------------------------------------------
SELECT c.relname AS view_name, count(a.attname) AS columns
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'ai_analytics'
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
 WHERE c.relkind = 'v'
 GROUP BY c.relname
 ORDER BY c.relname;

-- ROLLBACK (only if needed; removes the views, nothing else):
--   DROP SCHEMA ai_analytics CASCADE;
