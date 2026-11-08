-- ai_usage table and the write-only role ai_writer. Run on ai_ops AFTER 01-create-ai-ops-db.sql, as a superuser or the
-- database owner. The password is NOT in this file; you choose it and pass it as a psql variable:
--
--   psql -U <owner> -d ai_ops -v ON_ERROR_STOP=1 -v ai_password='<a long random password>' -f 02-ai-ops-schema.sql
--
-- Then set AI_OPS_DATABASE_URL in .env to postgresql+psycopg2://ai_writer:<password>@postgres:5432/ai_ops
-- NOT run automatically. Safe to run again (table/indexes IF NOT EXISTS; the role is created once, later runs only
-- reset the password and the grants).
--
-- ai_writer can: connect to ai_ops and INSERT into ai_usage (plus use its id sequence). It cannot SELECT, UPDATE,
-- DELETE, create anything, and is not a superuser. No question text and no raw user id are stored (actor = short hash).

CREATE TABLE IF NOT EXISTS ai_usage (
    id            bigserial PRIMARY KEY,
    ts            timestamptz NOT NULL DEFAULT now(),
    route         text,
    tier          text,
    provider      text,
    model         text,
    ok            boolean,
    error_code    text,
    latency_ms    integer,
    input_tokens  integer,
    output_tokens integer,
    estimated     boolean,
    actor         text,
    attempts      smallint
);
CREATE INDEX IF NOT EXISTS ai_usage_ts_idx ON ai_usage (ts);
CREATE INDEX IF NOT EXISTS ai_usage_route_ts_idx ON ai_usage (route, ts);

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_writer') AS create_role \gset
\if :create_role
  CREATE ROLE ai_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT CONNECTION LIMIT 5;
\endif
ALTER ROLE ai_writer PASSWORD :'ai_password';
ALTER ROLE ai_writer SET statement_timeout = '5s';
ALTER ROLE ai_writer SET idle_in_transaction_session_timeout = '15s';

-- connect to ai_ops only
REVOKE CONNECT ON DATABASE ai_ops FROM PUBLIC;
SELECT format('GRANT CONNECT ON DATABASE %I TO ai_writer', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO ai_writer;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_writer;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ai_writer;
GRANT INSERT ON ai_usage TO ai_writer;
GRANT USAGE ON SEQUENCE ai_usage_id_seq TO ai_writer;

-- VERIFY (read only; run after)
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin FROM pg_roles WHERE rolname = 'ai_writer';   -- t only for rolcanlogin
SELECT has_table_privilege('ai_writer', 'ai_usage', 'INSERT') AS can_insert,                                   -- true
       has_table_privilege('ai_writer', 'ai_usage', 'SELECT') AS can_select,                                   -- false
       has_table_privilege('ai_writer', 'ai_usage', 'UPDATE') AS can_update,                                   -- false
       has_table_privilege('ai_writer', 'ai_usage', 'DELETE') AS can_delete;                                   -- false
SELECT has_sequence_privilege('ai_writer', 'ai_usage_id_seq', 'USAGE') AS can_use_sequence;                    -- true

-- MANUAL PURGE (run by hand as the owner when you want to keep only 90 days; nothing runs this automatically):
--   DELETE FROM ai_usage WHERE ts < now() - interval '90 days';

-- ROLLBACK (only if needed):
--   REVOKE ALL ON ai_usage FROM ai_writer;
--   REVOKE ALL ON SEQUENCE ai_usage_id_seq FROM ai_writer;
--   REVOKE USAGE ON SCHEMA public FROM ai_writer;
--   REVOKE CONNECT ON DATABASE ai_ops FROM ai_writer;
--   DROP ROLE ai_writer;      -- fails while the role still has grants; revoke them first
--   DROP TABLE ai_usage;      -- only if you also want to delete the data
