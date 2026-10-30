-- DO NOT RUN. This migration is no longer needed for authorization and is kept only as a record.
--
-- Event-scoped authorization takes "who owns an event" only from staff_assignments (role OWNER, status ACTIVE); the
-- organizer profile in event_organizers (linked through event_organizer_mapping) is a display profile and decides nothing.
-- The legacy fallback that treated a profile owner as OWNER has been removed, so events without an OWNER ACTIVE row have
-- no owner by design (test data is cleaned up instead of back-filled).
--
-- What it does: for each event that has a profile owner and no ACTIVE OWNER row, insert one OWNER row for the profile's
-- user_id. It never changes an existing row: if the (event, user) pair already has a row (for example REVOKED), nothing
-- is inserted, and that pair is listed by the review query below for a person to decide.
-- Safe to run more than once (the second run inserts nothing).
--
-- id: staff_assignments.id is a text primary key filled by the application; gen_random_uuid() is built into PostgreSQL 13+.

-- PRE-CHECK 1 (read only): events that would get a row.
SELECT left(e.id::text, 8) AS event, left(o.user_id, 8) AS owner_profile_user, e.event_approval_status
FROM events e
JOIN event_organizer_mapping m ON m.event_id = e.id
JOIN event_organizers o ON o.id = m.organizer_id
WHERE NOT EXISTS (SELECT 1 FROM staff_assignments sa WHERE sa.event_id = e.id AND sa.role = 'OWNER' AND sa.status = 'ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM staff_assignments sa WHERE sa.event_id = e.id AND sa.user_id = o.user_id)
ORDER BY 1;

-- PRE-CHECK 2 (read only): profile owners that already have a non-ACTIVE row. These are NOT touched; decide by hand.
SELECT left(e.id::text, 8) AS event, left(o.user_id, 8) AS owner_profile_user, sa.role, sa.status
FROM events e
JOIN event_organizer_mapping m ON m.event_id = e.id
JOIN event_organizers o ON o.id = m.organizer_id
JOIN staff_assignments sa ON sa.event_id = e.id AND sa.user_id = o.user_id AND sa.status <> 'ACTIVE';

BEGIN;

INSERT INTO staff_assignments (id, event_id, user_id, name, role, status, assigned_at)
SELECT gen_random_uuid()::text, e.id, o.user_id, o.name, 'OWNER', 'ACTIVE', now()
FROM events e
JOIN event_organizer_mapping m ON m.event_id = e.id
JOIN event_organizers o ON o.id = m.organizer_id
WHERE NOT EXISTS (SELECT 1 FROM staff_assignments sa WHERE sa.event_id = e.id AND sa.role = 'OWNER' AND sa.status = 'ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM staff_assignments sa WHERE sa.event_id = e.id AND sa.user_id = o.user_id);

-- POST-CHECK: expect no rows. (An event with two mapped profiles would get two OWNER rows; PRE-CHECK 1 shows that case
-- as two lines for one event, review it before COMMIT.)
SELECT left(e.id::text, 8) AS event_still_without_owner_row
FROM events e
JOIN event_organizer_mapping m ON m.event_id = e.id
WHERE NOT EXISTS (SELECT 1 FROM staff_assignments sa WHERE sa.event_id = e.id AND sa.role = 'OWNER' AND sa.status = 'ACTIVE')
  AND NOT EXISTS (
      SELECT 1 FROM event_organizers o JOIN staff_assignments sa2 ON sa2.user_id = o.user_id AND sa2.event_id = e.id
      WHERE o.id = m.organizer_id AND sa2.status <> 'ACTIVE');

COMMIT;
