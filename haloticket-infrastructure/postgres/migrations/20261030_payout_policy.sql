CREATE TABLE IF NOT EXISTS payout_policies (
    id VARCHAR(255) PRIMARY KEY,
    advance_rate INTEGER NOT NULL CHECK (advance_rate BETWEEN 0 AND 100),
    advance_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    processing_time VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(255),
    reason TEXT,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payout_policy_overrides (
    id VARCHAR(255) PRIMARY KEY,
    scope VARCHAR(32) NOT NULL CHECK (scope IN ('EVENT', 'ORGANIZER')),
    target_id VARCHAR(255) NOT NULL,
    advance_rate INTEGER NOT NULL CHECK (advance_rate BETWEEN 0 AND 100),
    advance_enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(255),
    reason TEXT,
    updated_at TIMESTAMP,
    UNIQUE(scope, target_id)
);

ALTER TABLE payout_policies ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payout_policy_overrides ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

INSERT INTO payout_policies (id, advance_rate, advance_enabled, processing_time, version, row_version)
VALUES ('GLOBAL', 30, FALSE, 'Theo chính sách vận hành', 0, 0)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS payout_policy_audit (
    id VARCHAR(255) PRIMARY KEY,
    scope VARCHAR(32) NOT NULL,
    target_id VARCHAR(255),
    old_advance_rate INTEGER,
    new_advance_rate INTEGER,
    old_enabled BOOLEAN,
    new_enabled BOOLEAN,
    old_processing_time VARCHAR(255),
    new_processing_time VARCHAR(255),
    actor_id VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    changed_at TIMESTAMP NOT NULL
);
