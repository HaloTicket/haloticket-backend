-- Read-only database role for the AI data analysis. Run on event_db AFTER 01-ai-analytics-views.sql, as a superuser or
-- the database owner. The password is NOT in this file; you choose it and pass it as a psql variable:
--
--   psql -U <owner> -d event_db -v ON_ERROR_STOP=1 -v ai_password='<a long random password>' -f 02-ai-readonly-role.sql
--
-- Then put the same user and password in the ai-assistant environment (AI_DB_USER=ai_readonly, AI_DB_PASSWORD=...).
-- NOT run automatically. Safe to run again (the role is created once, later runs only reset the password and the grants).
--
-- The role can: connect to this database, use the ai_analytics schema, SELECT from the views in it. It cannot create
-- anything, cannot write, is not a superuser, and has no grant on any table in schema public.

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_readonly') AS create_role \gset
\if :create_role
  CREATE ROLE ai_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT CONNECTION LIMIT 10;
\endif
ALTER ROLE ai_readonly PASSWORD :'ai_password';

-- every session of this role is read only, has a statement timeout and finds the views without a schema prefix
ALTER ROLE ai_readonly SET default_transaction_read_only = on;
ALTER ROLE ai_readonly SET statement_timeout = '8s';
ALTER ROLE ai_readonly SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE ai_readonly SET search_path = ai_analytics;

SELECT format('GRANT CONNECT ON DATABASE %I TO ai_readonly', current_database()) \gexec
GRANT USAGE ON SCHEMA ai_analytics TO ai_readonly;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_analytics FROM ai_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA ai_analytics TO ai_readonly;   -- "tables" includes the views of the schema

-- VERIFY (read only; run after, all three must show what the comment says)
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin FROM pg_roles WHERE rolname = 'ai_readonly';  -- t only for rolcanlogin
SELECT c.relname, has_table_privilege('ai_readonly', c.oid, 'SELECT') AS can_select
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'ai_analytics' AND c.relkind = 'v' ORDER BY 1;                                              -- all true
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'f', 'm') AND has_table_privilege('ai_readonly', c.oid, 'SELECT');  -- no rows

-- ROLLBACK (only if needed):
--   REVOKE ALL ON ALL TABLES IN SCHEMA ai_analytics FROM ai_readonly;
--   REVOKE USAGE ON SCHEMA ai_analytics FROM ai_readonly;
--   DROP ROLE ai_readonly;
