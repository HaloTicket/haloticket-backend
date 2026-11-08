-- Read-only database role for the customer chatbot, part 2: ticket_db. Run on ticket_db AFTER 01-ai-chatbot-ro-event-db.sql
-- (same role name, same password: pass the same -v ai_password value), as a superuser or the database owner:
--
--   psql -U <owner> -d ticket_db -v ON_ERROR_STOP=1 -v ai_password='<the same password as part 1>' -f 02-ai-chatbot-ro-ticket-db.sql
--
-- NOT run automatically. Safe to run again (the role is created once if missing, later runs reset the password and grants).
--
-- Column-level SELECT only. Names checked against ticket-service entities TicketType, SeatLayout, Seat, SeatRow, SeatZone.
-- For a "which seats are SOLD / LOCKED" query the chatbot needs this join (all columns below are granted):
--   seats.id, seats.seat_number, seats.seat_status (AVAILABLE, LOCKED, SOLD, BLOCKED), seats.seat_type,
--   seats.seat_row_id -> seat_rows.id, seat_rows.label, seat_rows.seat_zone_id -> seat_zones.id, seat_zones.name,
--   seat_zones.ticket_type_id, seat_zones.seat_layout_id -> seat_layouts.id, seat_layouts.event_id.
-- Seat coordinates, ticket links, version, SVG and zone geometry are NOT granted. Redis seat holds are not visible here.

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_chatbot_ro') AS create_role \gset
\if :create_role
  CREATE ROLE ai_chatbot_ro LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT CONNECTION LIMIT 10;
\endif
ALTER ROLE ai_chatbot_ro PASSWORD :'ai_password';
-- role settings are cluster-wide (same values as part 1; harmless to set twice)
ALTER ROLE ai_chatbot_ro SET default_transaction_read_only = on;
ALTER ROLE ai_chatbot_ro SET statement_timeout = '8s';
ALTER ROLE ai_chatbot_ro SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE ai_chatbot_ro SET search_path = public;

SELECT format('GRANT CONNECT ON DATABASE %I TO ai_chatbot_ro', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO ai_chatbot_ro;

REVOKE ALL ON ticket_types, seat_layouts, seats, seat_rows, seat_zones FROM ai_chatbot_ro;
GRANT SELECT (id, event_id, showtime_id, name, price, description, ticket_type_status, total_quantity, min_per_order, max_per_order)
    ON ticket_types TO ai_chatbot_ro;
GRANT SELECT (id, event_id, draft_json, image_url, status, updated_at)
    ON seat_layouts TO ai_chatbot_ro;
GRANT SELECT (id, seat_row_id, seat_number, seat_type, seat_status)
    ON seats TO ai_chatbot_ro;
GRANT SELECT (id, seat_zone_id, label)
    ON seat_rows TO ai_chatbot_ro;
GRANT SELECT (id, seat_layout_id, name, ticket_type_id)
    ON seat_zones TO ai_chatbot_ro;

-- VERIFY (read only; run after)
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin FROM pg_roles WHERE rolname = 'ai_chatbot_ro';  -- t only for rolcanlogin
SELECT table_name, string_agg(column_name, ', ' ORDER BY column_name) AS granted_select_columns
  FROM information_schema.column_privileges
 WHERE grantee = 'ai_chatbot_ro' AND privilege_type = 'SELECT' AND table_schema = 'public'
 GROUP BY table_name ORDER BY 1;                                                                                -- exactly the five tables above
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'f', 'm')
   AND has_table_privilege('ai_chatbot_ro', c.oid, 'SELECT');                                                   -- no rows
SELECT has_column_privilege('ai_chatbot_ro', 'ticket_types', 'reserved_quantity', 'SELECT') AS can_read_reserved;  -- false

-- ROLLBACK (only if needed):
--   REVOKE ALL ON ticket_types, seat_layouts, seats, seat_rows, seat_zones FROM ai_chatbot_ro;
--   REVOKE USAGE ON SCHEMA public FROM ai_chatbot_ro;
--   REVOKE CONNECT ON DATABASE ticket_db FROM ai_chatbot_ro;
