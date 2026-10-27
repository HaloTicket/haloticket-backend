-- Database-side filter for organizer analysis. Run on event_db AFTER 01-ai-analytics-views.sql, as the same
-- owner that ran it:
--
--   psql -U <owner> -d event_db -v ON_ERROR_STOP=1 -f 03-ai-organizer-scope.sql
--
-- NOT run automatically. Safe to run again. Run it BEFORE setting AI_ANALYSIS_ORGANIZER_ENABLED=true.
--
-- What it does: the views that hold an event id now show a row only when the event is in the session setting
-- app.allowed_event_ids, which ai-assistant sets for every analysis query inside its own transaction:
--   '*'                    an administrator: all events
--   'id1,id2,...'          an organizer: the events they manage
--   empty or not set       nothing (fail closed)
-- ai-assistant already limits the query itself (it rewrites every use of these views to the caller's events). This is the
-- second lock: even if a generated query got past that, the database would still return only the caller's events.
-- The views also become security_barrier, so a condition written by the model cannot be evaluated before the filter.
--
-- Views without an event id (v_venues, v_event_categories, v_search_terms) are left as they are.
-- Once this has run, an analysis session that does NOT set app.allowed_event_ids sees no events at all, so deploy the
-- ai-assistant version that sets it first (it is in the same release as this file).

CREATE OR REPLACE FUNCTION ai_analytics.in_scope(event_id text) RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE
AS $fn$
  SELECT CASE
           WHEN coalesce(current_setting('app.allowed_event_ids', true), '') = '*' THEN true
           ELSE event_id = ANY (string_to_array(coalesce(current_setting('app.allowed_event_ids', true), ''), ','))
         END
$fn$;
COMMENT ON FUNCTION ai_analytics.in_scope(text) IS 'true when the event id is in the session setting app.allowed_event_ids (or the setting is *).';

CREATE OR REPLACE VIEW ai_analytics.v_events WITH (security_barrier = true) AS
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
  LEFT JOIN event_categories c ON c.id = e.category_id
 WHERE ai_analytics.in_scope(e.id::text);

CREATE OR REPLACE VIEW ai_analytics.v_event_showtimes WITH (security_barrier = true) AS
SELECT s.event_id        AS event_id,
       s.id              AS showtime_id,
       s.name            AS showtime_name,
       s.start_time      AS start_time,
       s.end_time        AS end_time,
       s.sale_start_time AS sale_start_time,
       s.sale_end_time   AS sale_end_time
  FROM event_showtimes s
 WHERE ai_analytics.in_scope(s.event_id::text);

CREATE OR REPLACE VIEW ai_analytics.v_event_views_daily WITH (security_barrier = true) AS
SELECT l.event_id                      AS event_id,
       l.viewed_at::date               AS view_date,
       count(*)                        AS page_views,
       count(DISTINCT l.session_id)    AS unique_sessions
  FROM event_view_logs l
 WHERE ai_analytics.in_scope(l.event_id::text)
 GROUP BY l.event_id, l.viewed_at::date;

CREATE OR REPLACE VIEW ai_analytics.v_vouchers WITH (security_barrier = true) AS
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
  FROM vouchers v
 WHERE ai_analytics.in_scope(v.event_id::text);

DO $views$
BEGIN
  IF to_regclass('public.ticket_types') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_ticket_types WITH (security_barrier = true) AS
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
     WHERE ai_analytics.in_scope(t.event_id::text)
    $v$;
  END IF;

  IF to_regclass('public.tickets') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_ticket_sales_daily WITH (security_barrier = true) AS
          SELECT t.event_id                          AS event_id,
                 t.ticket_type_id                    AS ticket_type_id,
                 COALESCE(t.issued_at, t.created_at)::date AS sale_date,
                 count(*)                            AS tickets_sold,
                 count(*) FILTER (WHERE t.status = 'USED') AS tickets_checked_in,
                 COALESCE(sum(t.purchase_price), 0)  AS revenue
            FROM tickets t
           WHERE t.status <> 'REVOKED'
             AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY')
             AND ai_analytics.in_scope(t.event_id::text)
           GROUP BY t.event_id, t.ticket_type_id, COALESCE(t.issued_at, t.created_at)::date
    $v$;
  END IF;

  IF to_regclass('public.bookings') IS NOT NULL THEN
    EXECUTE $v$
      CREATE OR REPLACE VIEW ai_analytics.v_booking_summary_daily WITH (security_barrier = true) AS
          SELECT b.event_id               AS event_id,
                 b.created_at::date       AS booking_date,
                 b.status                 AS status,
                 count(*)                 AS bookings_count,
                 COALESCE(sum(b.total_amount), 0)    AS total_amount,
                 COALESCE(sum(b.discount_amount), 0) AS discount_amount
            FROM bookings b
           WHERE ai_analytics.in_scope(b.event_id::text)
           GROUP BY b.event_id, b.created_at::date, b.status
    $v$;
  END IF;

END
$views$;

-- VERIFY (read only): every view that holds an event id is now a security barrier
SELECT c.relname AS view_name, coalesce(c.reloptions::text, '') AS options
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'ai_analytics'
 WHERE c.relkind = 'v' ORDER BY c.relname;

-- ROLLBACK (only if needed): run 01-ai-analytics-views.sql again, which restores the unfiltered views.
--   DROP FUNCTION ai_analytics.in_scope(text);   -- only after the views no longer use it
