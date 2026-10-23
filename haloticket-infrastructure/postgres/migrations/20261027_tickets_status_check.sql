-- Apply to ticket_db.
--
-- Production's tickets_status_check allows only VALID, USED, TRANSFERED, REVOKED. TicketMarketplaceCommandListener writes
-- LOCKED_FOR_RESALE (listing a ticket for resale) and LOCKED_FOR_TRANSFER (sending a gift); the database rejects both, so
-- ticket-service cannot lock a ticket and every resale listing and every gift stays unfinished.
-- (20260917_ticket_operations.sql would have fixed this, but it was never applied to production.)
--
-- Allowed values after this file = every value of TicketStatus today. TRANSFERRED (two Rs, listed by the old
-- 20260917 file) is not in the enum and not in production's constraint, so no row can hold it and it is left out.
-- Safe to run more than once. Take a pg_dump of ticket_db first. Run it together with the marketplace_db migration
-- 20261015_marketplace_transfer_controls.sql (see the run order in the PR notes).
--
-- When you add a value to TicketStatus, add it to the list below (a test fails if you forget).

-- PRE-CHECK (read only): rows whose status is not in the new list. Expect no rows; if one shows up, stop,
-- because ADD CONSTRAINT below would fail on it.
SELECT status, count(*) AS rows_outside_new_list
FROM tickets
WHERE status NOT IN ('VALID', 'USED', 'TRANSFERED', 'REVOKED', 'LOCKED_FOR_RESALE', 'LOCKED_FOR_TRANSFER')
GROUP BY status;

BEGIN;

ALTER TABLE tickets DROP CONSTRAINT IF EXISTS tickets_status_check;

ALTER TABLE tickets
    ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('VALID', 'USED', 'TRANSFERED', 'REVOKED', 'LOCKED_FOR_RESALE', 'LOCKED_FOR_TRANSFER'));

COMMIT;

-- VERIFY (read only; run after the migration). Expect one row listing all six values.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.tickets'::regclass AND contype = 'c' AND conname = 'tickets_status_check';

-- ROLLBACK (only if needed, and only while no ticket is LOCKED_FOR_RESALE or LOCKED_FOR_TRANSFER, otherwise the first
-- statement fails). This restores what production had before:
--   BEGIN;
--   ALTER TABLE tickets DROP CONSTRAINT IF EXISTS tickets_status_check;
--   ALTER TABLE tickets ADD CONSTRAINT tickets_status_check CHECK (status IN ('VALID', 'USED', 'TRANSFERED', 'REVOKED'));
--   COMMIT;
