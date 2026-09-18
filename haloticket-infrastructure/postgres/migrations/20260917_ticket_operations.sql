-- Ticket Service UC07 support. Apply this file to ticket_db.
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS original_price NUMERIC(19,0);
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS purchase_price NUMERIC(19,0);
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS acquisition_type VARCHAR(30) NOT NULL DEFAULT 'PRIMARY';
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS lock_operation_id VARCHAR(255);
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS version BIGINT;
ALTER TABLE IF EXISTS tickets DROP CONSTRAINT IF EXISTS tickets_status_check;
ALTER TABLE IF EXISTS tickets ADD CONSTRAINT tickets_status_check CHECK (status IN ('VALID', 'USED', 'TRANSFERED', 'TRANSFERRED', 'REVOKED', 'LOCKED_FOR_RESALE', 'LOCKED_FOR_TRANSFER'));

CREATE TABLE IF NOT EXISTS ticket_operations (
    id VARCHAR(255) PRIMARY KEY,
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    saga_id VARCHAR(255),
    ticket_id VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255),
    expected_owner_id VARCHAR(255),
    purpose VARCHAR(30) NOT NULL,
    state VARCHAR(30) NOT NULL,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    version BIGINT
);
