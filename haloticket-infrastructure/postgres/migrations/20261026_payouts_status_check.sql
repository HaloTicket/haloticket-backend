-- Apply to payment_db. PayoutServiceImpl writes PayoutStatus.DISPUTE (admin "dispute" action), but the CHECK constraint
-- Hibernate created with the table only allows the first four values, so that action fails with a constraint error.
-- Hibernate (ddl-auto: update) never changes a CHECK constraint after the table exists.
--
-- Safe to run more than once. Check the current definition first (VERIFY query below): if production already lists
-- DISPUTE, there is nothing to do.
-- When you add a value to PayoutStatus, add it to the list below.

BEGIN;

ALTER TABLE payouts DROP CONSTRAINT IF EXISTS payouts_status_check;

ALTER TABLE payouts
    ADD CONSTRAINT payouts_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DISPUTE'));

COMMIT;

-- VERIFY (read only; run before and after). Expect one row listing all five values after the migration.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.payouts'::regclass AND contype = 'c';
