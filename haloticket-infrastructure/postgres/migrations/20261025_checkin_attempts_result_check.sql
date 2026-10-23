-- Apply to ticket_db BEFORE deploying a ticket-service that can write OUTSIDE_CHECKIN_WINDOW attempts.
--
-- Hibernate (ddl-auto: update) created checkin_attempts_result_check when the table was first created, with the
-- values the enum had then (SUCCESS, FAILED_SIGNATURE, FAILED_ALREADY_IN). It never changes a CHECK constraint
-- afterwards, so a newer CheckInAttemptResult value is rejected by the database on INSERT.
--
-- Safe to run more than once. The table is tiny, the swap is one short transaction.
-- When you add a value to CheckInAttemptResult, add it to the list below (a test fails if you forget).

BEGIN;

ALTER TABLE checkin_attempts DROP CONSTRAINT IF EXISTS checkin_attempts_result_check;

ALTER TABLE checkin_attempts
    ADD CONSTRAINT checkin_attempts_result_check
    CHECK (result IN ('SUCCESS', 'FAILED_SIGNATURE', 'FAILED_ALREADY_IN', 'OUTSIDE_CHECKIN_WINDOW'));

COMMIT;

-- VERIFY (read only; run after the migration). Expect exactly one row listing all four values.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.checkin_attempts'::regclass AND contype = 'c';
