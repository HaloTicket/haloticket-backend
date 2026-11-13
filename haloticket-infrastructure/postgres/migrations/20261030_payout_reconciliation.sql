CREATE TABLE IF NOT EXISTS payout_reconciliations (
    event_id VARCHAR(255) PRIMARY KEY,
    organizer_user_id VARCHAR(255) NOT NULL,
    gross_collected BIGINT NOT NULL CHECK (gross_collected >= 0),
    platform_fees BIGINT NOT NULL CHECK (platform_fees >= 0),
    refunds BIGINT NOT NULL CHECK (refunds >= 0),
    held_amount BIGINT NOT NULL CHECK (held_amount >= 0),
    confirmed_by VARCHAR(255),
    confirmed_at TIMESTAMP,
    reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE payout_reconciliations ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
