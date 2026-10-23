-- Apply to notification_db.
--
-- Production's notification_templates_type_check allows TICKET_CONFIRMATION, EVENT_REMINDER, SYSTEM_ALERT, EKYC_RESULT.
-- NotificationType also has EVENT_MEMBER_INVITATION. No code stores that type in notification_templates today (the staff
-- invitation email is rendered from a file template), so no feature fails now; this keeps the database equal to the enum.
--
-- Allowed values after this file = every value of NotificationType today. Safe to run more than once. Take a pg_dump of
-- notification_db first.
--
-- When you add a value to NotificationType, add it to the list below (a test fails if you forget).

-- PRE-CHECK (read only): rows whose type is not in the new list. Expect no rows.
SELECT type, count(*) AS rows_outside_new_list
FROM notification_templates
WHERE type NOT IN ('TICKET_CONFIRMATION', 'EVENT_REMINDER', 'SYSTEM_ALERT', 'EKYC_RESULT', 'EVENT_MEMBER_INVITATION')
GROUP BY type;

BEGIN;

ALTER TABLE notification_templates DROP CONSTRAINT IF EXISTS notification_templates_type_check;

ALTER TABLE notification_templates
    ADD CONSTRAINT notification_templates_type_check
    CHECK (type IN ('TICKET_CONFIRMATION', 'EVENT_REMINDER', 'SYSTEM_ALERT', 'EKYC_RESULT', 'EVENT_MEMBER_INVITATION'));

COMMIT;

-- VERIFY (read only; run after the migration). Expect one row listing all five values.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.notification_templates'::regclass AND contype = 'c' AND conname = 'notification_templates_type_check';

-- ROLLBACK (only if needed, and only while no template has type EVENT_MEMBER_INVITATION):
--   BEGIN;
--   ALTER TABLE notification_templates DROP CONSTRAINT IF EXISTS notification_templates_type_check;
--   ALTER TABLE notification_templates ADD CONSTRAINT notification_templates_type_check CHECK (type IN ('TICKET_CONFIRMATION', 'EVENT_REMINDER', 'SYSTEM_ALERT', 'EKYC_RESULT'));
--   COMMIT;
