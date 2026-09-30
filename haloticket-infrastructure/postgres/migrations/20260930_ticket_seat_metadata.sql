ALTER TABLE tickets ADD COLUMN IF NOT EXISTS seat_reference VARCHAR(255);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS seat_section VARCHAR(255);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS seat_row_label VARCHAR(255);
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS seat_number_label VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS tickets_booking_seat_reference_uq
    ON tickets (booking_id, seat_reference)
    WHERE booking_id IS NOT NULL AND seat_reference IS NOT NULL;
