-- Event Service resale policy. Apply this file to event_db.
CREATE TABLE IF NOT EXISTS resale_policies (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL,
    min_price_percent NUMERIC(7,3) NOT NULL,
    max_price_percent NUMERIC(7,3) NOT NULL,
    closes_at TIMESTAMPTZ,
    terms_version VARCHAR(100),
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_resale_policy_price_range CHECK (min_price_percent >= 0 AND max_price_percent >= min_price_percent)
);
