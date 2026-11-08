-- Creates the ai_ops database (AI usage metering). Run on the database "postgres" (not on ai_ops, it does not exist yet),
-- as a superuser or a role that can create databases:
--
--   psql -U <owner> -d postgres -v ON_ERROR_STOP=1 -f 01-create-ai-ops-db.sql
--
-- NOT run automatically (the init scripts only run on an empty data directory). Safe to run again: the database is
-- created only when it does not exist. Next: 02-ai-ops-schema.sql on ai_ops.

SELECT 'CREATE DATABASE ai_ops' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ai_ops') \gexec

-- VERIFY (read only; one row expected)
SELECT datname FROM pg_database WHERE datname = 'ai_ops';

-- ROLLBACK (only if needed, drops all metering data):
--   DROP DATABASE ai_ops;
