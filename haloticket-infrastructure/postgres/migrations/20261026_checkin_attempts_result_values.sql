-- Apply to ticket_db BEFORE deploying the ticket-service that stores refused scans (TICKET_NOT_FOUND, WRONG_EVENT,
-- WRONG_SHOWTIME, TICKET_LOCKED, WINDOW_UNVERIFIED). 20261025_checkin_attempts_result_check.sql is already applied in
-- production and stays as it is; this file replaces its constraint with the full list.
--
-- Safe to run more than once. The table is tiny, the swap is one short transaction.
-- If it is not applied, refused scans still get their normal answer (400 or 200 INVALID); the attempt row is simply
-- not stored and ticket-service logs "[CHECKIN_ATTEMPT_LOST]".
-- When you add a value to CheckInAttemptResult, add it to the list below (a test fails if you forget).

BEGIN;

ALTER TABLE checkin_attempts DROP CONSTRAINT IF EXISTS checkin_attempts_result_check;

ALTER TABLE checkin_attempts
    ADD CONSTRAINT checkin_attempts_result_check
    CHECK (result IN ('SUCCESS', 'FAILED_SIGNATURE', 'FAILED_ALREADY_IN', 'OUTSIDE_CHECKIN_WINDOW',
                      'TICKET_NOT_FOUND', 'WRONG_EVENT', 'WRONG_SHOWTIME', 'TICKET_LOCKED', 'WINDOW_UNVERIFIED'));

COMMIT;

-- VERIFY (read only; run after the migration). Expect one row listing all nine values.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.checkin_attempts'::regclass AND contype = 'c';
