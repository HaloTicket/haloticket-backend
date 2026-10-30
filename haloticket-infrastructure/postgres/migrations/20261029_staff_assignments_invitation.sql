-- Event member invitations (fix/member-invitation): columns for the invitation flow, and one row per person per event.
-- Run by hand against event_db, in this order, and only after reading the SELECT in step 2.
--
-- 1. The columns. Optional: event-service creates them itself at startup (spring.jpa.hibernate.ddl-auto=update), so running
--    this first only makes the deploy order explicit. Idempotent.
ALTER TABLE staff_assignments ADD COLUMN IF NOT EXISTS invitation_token_hash   varchar(255);
ALTER TABLE staff_assignments ADD COLUMN IF NOT EXISTS invitation_expires_at   timestamp;
ALTER TABLE staff_assignments ADD COLUMN IF NOT EXISTS invited_by              varchar(255);
ALTER TABLE staff_assignments ADD COLUMN IF NOT EXISTS invited_at              timestamp;
ALTER TABLE staff_assignments ADD COLUMN IF NOT EXISTS invitation_resend_count integer;

-- status has no CHECK constraint (only role does: OWNER, ADMIN, MANAGER, CHECKIN_STAFF), so PENDING and DECLINED need nothing.

-- 2. Look before step 3: this must return no rows, otherwise the unique index below fails (and the code that looks a member
--    up by event and user would fail with it). Decide by hand which duplicate to keep; nothing here deletes anything.
SELECT event_id, user_id, count(*) AS rows_for_the_person
FROM staff_assignments
GROUP BY event_id, user_id
HAVING count(*) > 1;

-- 3. One row per person per event. CONCURRENTLY cannot run inside a transaction block: run it on its own (no BEGIN).
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_staff_assignments_event_user ON staff_assignments (event_id, user_id);

-- To undo: DROP INDEX CONCURRENTLY IF EXISTS ux_staff_assignments_event_user;  (the columns can stay; they are unused when the
-- invitation flow is not deployed yet.)
