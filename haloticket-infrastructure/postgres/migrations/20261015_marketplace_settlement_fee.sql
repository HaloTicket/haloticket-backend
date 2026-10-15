-- Apply to marketplace_db before deploying the marketplace service.

ALTER TABLE marketplace_transactions
    ADD COLUMN IF NOT EXISTS service_fee_percent NUMERIC(7, 3) DEFAULT 5.00;

UPDATE marketplace_transactions SET service_fee_percent = 5.00 WHERE service_fee_percent IS NULL;
