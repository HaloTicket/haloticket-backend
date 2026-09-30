-- Phase 1: Event transfer settings schema. Apply this file to event_db.

-- Add missing columns to resale_policies
ALTER TABLE resale_policies ADD COLUMN IF NOT EXISTS opens_at TIMESTAMPTZ;
ALTER TABLE resale_policies ADD COLUMN IF NOT EXISTS ticket_type_scope VARCHAR(20) NOT NULL DEFAULT 'ALL';

-- Resale policy ticket type scoping
CREATE TABLE IF NOT EXISTS resale_policy_ticket_types (
    policy_id VARCHAR(255) NOT NULL,
    ticket_type_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (policy_id, ticket_type_id),
    FOREIGN KEY (policy_id) REFERENCES resale_policies(id) ON DELETE CASCADE
);

-- Gift policies
CREATE TABLE IF NOT EXISTS gift_policies (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT false,
    opens_at TIMESTAMPTZ,
    closes_at TIMESTAMPTZ,
    invitation_ttl_hours INT NOT NULL DEFAULT 24,
    ticket_type_scope VARCHAR(20) NOT NULL DEFAULT 'ALL',
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS gift_policy_ticket_types (
    policy_id VARCHAR(255) NOT NULL,
    ticket_type_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (policy_id, ticket_type_id),
    FOREIGN KEY (policy_id) REFERENCES gift_policies(id) ON DELETE CASCADE
);

-- Transfer policy outbox (event-service publishes to Kafka)
CREATE TABLE IF NOT EXISTS transfer_policy_outbox (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_transfer_outbox_status_next ON transfer_policy_outbox(status, next_attempt_at)
    WHERE status = 'PENDING';

-- Transfer policy audits
CREATE TABLE IF NOT EXISTS transfer_policy_audits (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    feature VARCHAR(20) NOT NULL,
    actor VARCHAR(255),
    version BIGINT,
    before_json TEXT,
    after_json TEXT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_transfer_audits_event ON transfer_policy_audits(event_id, changed_at);
