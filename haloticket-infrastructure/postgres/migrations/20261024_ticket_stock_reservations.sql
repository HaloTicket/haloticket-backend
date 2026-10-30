-- Apply to ticket_db before deploying the booking-service that holds general-admission stock in ticket-service.
-- Hibernate (ddl-auto: update) also creates these objects, but adds reserved_quantity as a nullable column
-- without a default; this script makes it NOT NULL DEFAULT 0 and is safe to rerun.

ALTER TABLE ticket_types ADD COLUMN IF NOT EXISTS reserved_quantity integer;
UPDATE ticket_types SET reserved_quantity = 0 WHERE reserved_quantity IS NULL;
ALTER TABLE ticket_types ALTER COLUMN reserved_quantity SET DEFAULT 0;
ALTER TABLE ticket_types ALTER COLUMN reserved_quantity SET NOT NULL;

CREATE TABLE IF NOT EXISTS ticket_stock_reservations (
    id             varchar(255) PRIMARY KEY,
    booking_id     varchar(255) NOT NULL,
    ticket_type_id varchar(255) NOT NULL,
    quantity       integer      NOT NULL CHECK (quantity > 0),
    status         varchar(16)  NOT NULL CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    expires_at     timestamptz  NOT NULL,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT uk_stock_reservation_booking_type UNIQUE (booking_id, ticket_type_id)
);

CREATE INDEX IF NOT EXISTS idx_stock_reservation_status_expires
    ON ticket_stock_reservations (status, expires_at);
