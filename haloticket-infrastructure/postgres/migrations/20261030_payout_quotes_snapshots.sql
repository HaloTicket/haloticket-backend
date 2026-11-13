ALTER TABLE payouts ADD COLUMN IF NOT EXISTS request_type VARCHAR(32);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS quote_id VARCHAR(64);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS account_version BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS account_holder_snapshot VARCHAR(255);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS account_number_snapshot VARCHAR(255);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS bank_name_snapshot VARCHAR(255);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS transfer_reference VARCHAR(255);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS transfer_receipt_url TEXT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS public_reason TEXT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS processing_at TIMESTAMP;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS requested_amount_vnd BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS advance_rate_snapshot INTEGER;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS policy_version_snapshot BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS policy_scope_snapshot VARCHAR(32);
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS available_amount_snapshot BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS advance_base_snapshot BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS advance_limit_snapshot BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS paid_amount_snapshot BIGINT;
ALTER TABLE payouts ADD COLUMN IF NOT EXISTS reserved_amount_snapshot BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS payouts_idempotency_key_uq ON payouts(idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS payout_quotes (
    id VARCHAR(64) PRIMARY KEY,
    organizer_user_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    request_type VARCHAR(32) NOT NULL,
    request_amount BIGINT NOT NULL CHECK (request_amount > 0),
    available_amount BIGINT NOT NULL CHECK (available_amount >= 0),
    gross_snapshot BIGINT NOT NULL CHECK (gross_snapshot >= 0),
    platform_fees_snapshot BIGINT NOT NULL CHECK (platform_fees_snapshot >= 0),
    advance_rate_snapshot INTEGER NOT NULL,
    policy_version_snapshot BIGINT NOT NULL,
    policy_scope_snapshot VARCHAR(32) NOT NULL,
    advance_base_snapshot BIGINT NOT NULL,
    advance_limit_snapshot BIGINT NOT NULL,
    paid_amount_snapshot BIGINT NOT NULL,
    reserved_amount_snapshot BIGINT NOT NULL,
    account_version BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);
