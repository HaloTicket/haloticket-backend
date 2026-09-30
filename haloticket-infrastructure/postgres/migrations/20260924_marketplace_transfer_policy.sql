-- Phase 2: Marketplace transfer policy gate. Apply this file to marketplace_db.

CREATE TABLE IF NOT EXISTS event_transfer_policy_state (
    event_id VARCHAR(255) PRIMARY KEY,
    resale_enabled BOOLEAN NOT NULL DEFAULT true,
    resale_version BIGINT,
    gift_enabled BOOLEAN NOT NULL DEFAULT true,
    gift_version BIGINT,
    resale_opens_at TIMESTAMPTZ,
    resale_closes_at TIMESTAMPTZ,
    gift_opens_at TIMESTAMPTZ,
    gift_closes_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
