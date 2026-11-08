-- Admin intelligence storage (risk assessments, approval decisions, pinned reports, dashboard summaries) and its role
-- ai_admin_rw. Run on the database ai_ops (created by ai-ops/01), as a superuser or the database owner. The password is
-- NOT in this file; you choose it and pass it as a psql variable:
--
--   psql -U <owner> -d ai_ops -v ON_ERROR_STOP=1 -v ai_password='<a long random password>' -f 01-ai-admin-schema.sql
--
-- Then set AI_ADMIN_DATABASE_URL in .env to postgresql+psycopg2://ai_admin_rw:<password>@postgres:5432/ai_ops
-- (the password must not contain @ / : ? #). NOT run automatically. Safe to run again (tables IF NOT EXISTS; the role is
-- created once, later runs only reset the password and the grants).
--
-- ai_admin_rw is NOT ai_readonly (analysis, SELECT on views) and NOT ai_writer (INSERT only on ai_usage). It can only touch
-- the four tables below. It cannot create objects, is not a superuser and cannot read ai_usage.

CREATE TABLE IF NOT EXISTS event_risk_assessments (
    id              bigserial PRIMARY KEY,
    event_id        text        NOT NULL,
    content_hash    text        NOT NULL,
    ruleset_version text        NOT NULL,
    score           integer     NOT NULL CHECK (score BETWEEN 0 AND 100),
    level           text        NOT NULL CHECK (level IN ('LOW', 'MEDIUM', 'HIGH')),
    complete        boolean     NOT NULL,
    reasons         jsonb       NOT NULL,
    coverage        jsonb       NOT NULL,
    ai_layer        text        NOT NULL CHECK (ai_layer IN ('ran', 'failed', 'skipped')),
    trigger         text        NOT NULL CHECK (trigger IN ('auto', 'manual', 'queue')),
    created_by      text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS event_risk_assessments_event_idx ON event_risk_assessments (event_id, created_at DESC);
CREATE INDEX IF NOT EXISTS event_risk_assessments_hash_idx ON event_risk_assessments (event_id, content_hash);

CREATE TABLE IF NOT EXISTS event_risk_decisions (
    id            bigserial PRIMARY KEY,
    event_id      text        NOT NULL,
    assessment_id bigint,
    decision      text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason        text,
    decided_by    text,
    decided_at    timestamptz NOT NULL DEFAULT now(),
    source        text        NOT NULL CHECK (source IN ('web', 'derived'))
);
CREATE INDEX IF NOT EXISTS event_risk_decisions_event_idx ON event_risk_decisions (event_id, decided_at DESC);

CREATE TABLE IF NOT EXISTS ai_admin_pins (
    pin_id     text PRIMARY KEY,
    admin_sub  text        NOT NULL,
    title      text        NOT NULL,
    question   text        NOT NULL,
    sql_text   text        NOT NULL,
    chart_type text        NOT NULL,
    snapshot   jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ai_admin_pins_admin_idx ON ai_admin_pins (admin_sub, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_dashboard_summaries (
    day        date        NOT NULL,
    lang       text        NOT NULL,
    summary    text        NOT NULL,
    kpi_hash   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, lang)
);

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_admin_rw') AS create_role \gset
\if :create_role
  CREATE ROLE ai_admin_rw LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT CONNECTION LIMIT 8;
\endif
ALTER ROLE ai_admin_rw PASSWORD :'ai_password';
ALTER ROLE ai_admin_rw SET statement_timeout = '5s';
ALTER ROLE ai_admin_rw SET idle_in_transaction_session_timeout = '15s';

SELECT format('GRANT CONNECT ON DATABASE %I TO ai_admin_rw', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO ai_admin_rw;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ai_admin_rw;
GRANT SELECT, INSERT ON event_risk_assessments, event_risk_decisions TO ai_admin_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_admin_pins TO ai_admin_rw;
GRANT SELECT, INSERT, UPDATE ON ai_dashboard_summaries TO ai_admin_rw;
GRANT USAGE ON SEQUENCE event_risk_assessments_id_seq, event_risk_decisions_id_seq TO ai_admin_rw;

-- VERIFY (read only). Expected: 4 tables with the privileges above, none on ai_usage.
SELECT table_name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges
FROM information_schema.role_table_grants WHERE grantee = 'ai_admin_rw' GROUP BY table_name ORDER BY table_name;

-- ROLLBACK (only if needed; drops the stored assessments, decisions, pins and summaries):
--   DROP TABLE IF EXISTS event_risk_assessments, event_risk_decisions, ai_admin_pins, ai_dashboard_summaries;
--   DROP OWNED BY ai_admin_rw; DROP ROLE ai_admin_rw;
-- OPTIONAL retention, run by hand when wanted (assessments older than 180 days):
--   DELETE FROM event_risk_assessments WHERE created_at < now() - interval '180 days';
