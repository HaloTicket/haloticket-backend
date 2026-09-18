-- Marketplace Service UC07. Apply this file to marketplace_db.
CREATE TABLE IF NOT EXISTS marketplace_sagas (
    id VARCHAR(255) PRIMARY KEY,
    type VARCHAR(80) NOT NULL,
    ticket_id VARCHAR(255) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    business_id VARCHAR(255),
    state VARCHAR(40) NOT NULL,
    step VARCHAR(80) NOT NULL,
    payload TEXT,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(128) NOT NULL,
    deadline_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ticket_listings (
    id VARCHAR(255) PRIMARY KEY,
    ticket_id VARCHAR(255) NOT NULL UNIQUE,
    seller_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255),
    price NUMERIC(19,0) NOT NULL,
    original_price NUMERIC(19,0),
    min_price_snapshot NUMERIC(19,0),
    max_price_snapshot NUMERIC(19,0),
    status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    policy_version BIGINT,
    terms_version VARCHAR(100),
    saga_id VARCHAR(255) NOT NULL UNIQUE,
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS marketplace_transactions (
    id VARCHAR(255) PRIMARY KEY,
    ticket_listing_id VARCHAR(255) NOT NULL REFERENCES ticket_listings(id),
    buyer_id VARCHAR(255) NOT NULL,
    amount NUMERIC(19,0) NOT NULL,
    status VARCHAR(30) NOT NULL,
    payment_id VARCHAR(255),
    reservation_expires_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    saga_id VARCHAR(255),
    version BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ticket_transfers (
    id VARCHAR(255) PRIMARY KEY,
    marketplace_transaction_id VARCHAR(255) UNIQUE REFERENCES marketplace_transactions(id),
    ticket_id VARCHAR(255) NOT NULL,
    from_user_id VARCHAR(255) NOT NULL,
    to_user_id VARCHAR(255),
    recipient_email VARCHAR(320),
    type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    invitation_token_hash VARCHAR(128),
    invitation_expires_at TIMESTAMPTZ,
    message TEXT,
    cancellation_reason VARCHAR(100),
    saga_id VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    transferred_at TIMESTAMPTZ,
    version BIGINT
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(255) PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS processed_events (
    id VARCHAR(255) PRIMARY KEY,
    consumer_name VARCHAR(120) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_processed_consumer_message UNIQUE (consumer_name, message_id)
);

CREATE INDEX IF NOT EXISTS idx_marketplace_listing_public ON ticket_listings(status, published_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_marketplace_outbox_pending ON outbox_events(status, created_at);

-- Keep database constraints aligned with the application enum when upgrading an
-- existing marketplace database created by Hibernate.
ALTER TABLE IF EXISTS marketplace_transactions
    DROP CONSTRAINT IF EXISTS marketplace_transactions_status_check;
ALTER TABLE IF EXISTS marketplace_transactions
    ADD CONSTRAINT marketplace_transactions_status_check CHECK (status IN (
        'PENDING_PAYMENT', 'PAYMENT_PROCESSING', 'TRANSFERRING', 'COMPLETED',
        'REFUND_PENDING', 'REFUNDED', 'CANCELLED', 'EXPIRED'
    ));
ALTER TABLE IF EXISTS ticket_listings
    DROP CONSTRAINT IF EXISTS ticket_listings_status_check;
ALTER TABLE IF EXISTS ticket_listings
    ADD CONSTRAINT ticket_listings_status_check CHECK (status IN (
        'ACTIVE', 'RESERVED', 'SOLD', 'CANCELLED', 'EXPIRED'
    ));
