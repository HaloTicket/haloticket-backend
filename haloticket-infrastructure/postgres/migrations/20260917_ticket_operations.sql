-- Ticket Service UC07 support. Apply this file to ticket_db.
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS original_price NUMERIC(19,0);
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS lock_operation_id VARCHAR(255);
ALTER TABLE IF EXISTS tickets ADD COLUMN IF NOT EXISTS version BIGINT;

CREATE TABLE IF NOT EXISTS ticket_operations (
    id VARCHAR(255) PRIMARY KEY,
    operation_id VARCHAR(255) NOT NULL UNIQUE,
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
