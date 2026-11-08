-- Read-only database role for the customer chatbot (chat-search and smart-booking-chat), part 1: event_db.
-- Run on event_db as a superuser or the database owner. The password is NOT in this file; you choose it and pass it
-- as a psql variable (use the SAME password when you run 02-ai-chatbot-ro-ticket-db.sql, the role is cluster-wide):
--
--   psql -U <owner> -d event_db -v ON_ERROR_STOP=1 -v ai_password='<a long random password>' -f 01-ai-chatbot-ro-event-db.sql
--
-- Then set, in the VPS .env, the full URLs (see .env.example):
--   CHATBOT_EVENT_DATABASE_URL=postgresql+psycopg2://ai_chatbot_ro:<password>@postgres:5432/event_db
--   CHATBOT_TICKET_DATABASE_URL=postgresql+psycopg2://ai_chatbot_ro:<password>@postgres:5432/ticket_db
-- NOT run automatically. Safe to run again (the role is created once, later runs reset the password and the grants).
--
-- The role can: connect, and SELECT only the listed COLUMNS of three tables (column-level grants). It cannot read any
-- other column or table, write, create anything, and is not a superuser. It does NOT touch and does not depend on the
-- postgres_fdw foreign tables that exist in event_db on the VPS (ticket_types, tickets, bookings, booking_items).
-- Column names checked against event-service Event.java / EventShowtime.java (Spring snake_case naming).
-- Event.java has a real column is_private (field isPrivate); it is granted so private events can be filtered out.

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_chatbot_ro') AS create_role \gset
\if :create_role
  CREATE ROLE ai_chatbot_ro LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT CONNECTION LIMIT 10;
\endif
ALTER ROLE ai_chatbot_ro PASSWORD :'ai_password';

-- every session of this role is read only, has timeouts, and resolves pgvector operators in schema public
ALTER ROLE ai_chatbot_ro SET default_transaction_read_only = on;
ALTER ROLE ai_chatbot_ro SET statement_timeout = '8s';
ALTER ROLE ai_chatbot_ro SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE ai_chatbot_ro SET search_path = public;

SELECT format('GRANT CONNECT ON DATABASE %I TO ai_chatbot_ro', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO ai_chatbot_ro;

-- start clean on the three tables, then grant exactly the columns the chatbot needs
REVOKE ALL ON events, event_showtimes, event_vectors FROM ai_chatbot_ro;
GRANT SELECT (id, title, description, banner_url, thumbnail_url, location_name, city, ward, address, dates_summary,
              min_price, event_status, event_approval_status, start_time, end_time, sale_start_time, sale_end_time,
              created_at, is_private)
    ON events TO ai_chatbot_ro;
GRANT SELECT (id, event_id, name, start_time, end_time, sale_start_time, sale_end_time)
    ON event_showtimes TO ai_chatbot_ro;
GRANT SELECT (event_id, content, embedding)
    ON event_vectors TO ai_chatbot_ro;

-- VERIFY (read only; run after)
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin FROM pg_roles WHERE rolname = 'ai_chatbot_ro';  -- t only for rolcanlogin
SELECT table_name, string_agg(column_name, ', ' ORDER BY column_name) AS granted_select_columns
  FROM information_schema.column_privileges
 WHERE grantee = 'ai_chatbot_ro' AND privilege_type = 'SELECT' AND table_schema = 'public'
 GROUP BY table_name ORDER BY 1;                                                                                -- exactly events, event_showtimes, event_vectors
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'f', 'm')
   AND has_table_privilege('ai_chatbot_ro', c.oid, 'SELECT');                                                   -- no rows (table-level SELECT is never granted)
SELECT has_column_privilege('ai_chatbot_ro', 'events', 'rejection_reason', 'SELECT') AS can_read_rejection_reason;   -- false

-- ROLLBACK (only if needed; do the ticket_db rollback too, then drop the role):
--   REVOKE ALL ON events, event_showtimes, event_vectors FROM ai_chatbot_ro;
--   REVOKE USAGE ON SCHEMA public FROM ai_chatbot_ro;
--   REVOKE CONNECT ON DATABASE event_db FROM ai_chatbot_ro;
--   DROP ROLE ai_chatbot_ro;   -- only after the same cleanup in ticket_db
