-- Apply to booking_db.
--
-- Production's bookings_status_check allows PENDING, PAID, CANCELLED, EXPIRED, REFUNDED. BookingStatus also has CONFIRMED.
-- Nothing in the code sets CONFIRMED today (AdminBookingController only accepts a booking that is PAID or CONFIRMED), so no
-- feature fails now; this keeps the database equal to the enum so the first code that sets it does not break.
--
-- Allowed values after this file = every value of BookingStatus today. Safe to run more than once. Take a pg_dump of
-- booking_db first.
--
-- When you add a value to BookingStatus, add it to the list below (a test fails if you forget).

-- PRE-CHECK (read only): rows whose status is not in the new list. Expect no rows.
SELECT status, count(*) AS rows_outside_new_list
FROM bookings
WHERE status NOT IN ('PENDING', 'PAID', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'REFUNDED')
GROUP BY status;

BEGIN;

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('PENDING', 'PAID', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'REFUNDED'));

COMMIT;

-- VERIFY (read only; run after the migration). Expect one row listing all six values.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.bookings'::regclass AND contype = 'c' AND conname = 'bookings_status_check';

-- ROLLBACK (only if needed, and only while no booking is CONFIRMED):
--   BEGIN;
--   ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_status_check;
--   ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUNDED'));
--   COMMIT;
