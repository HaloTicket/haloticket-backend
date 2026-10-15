-- Apply to event_db after 20260924_event_transfer_settings.sql.
BEGIN;

ALTER TABLE resale_policies ADD COLUMN IF NOT EXISTS service_fee_percent NUMERIC(7,3) DEFAULT 5;
ALTER TABLE resale_policies ADD COLUMN IF NOT EXISTS require_approval BOOLEAN DEFAULT false;
UPDATE resale_policies SET service_fee_percent = 5 WHERE service_fee_percent IS NULL;
UPDATE resale_policies SET require_approval = false WHERE require_approval IS NULL;
ALTER TABLE resale_policies ALTER COLUMN service_fee_percent SET DEFAULT 5;
ALTER TABLE resale_policies ALTER COLUMN service_fee_percent SET NOT NULL;
ALTER TABLE resale_policies ALTER COLUMN require_approval SET DEFAULT false;
ALTER TABLE resale_policies ALTER COLUMN require_approval SET NOT NULL;

ALTER TABLE gift_policies ADD COLUMN IF NOT EXISTS require_ekyc BOOLEAN DEFAULT true;
ALTER TABLE gift_policies ADD COLUMN IF NOT EXISTS max_gift_count INT DEFAULT 3;
UPDATE gift_policies SET require_ekyc = true WHERE require_ekyc IS NULL;
UPDATE gift_policies SET max_gift_count = 3 WHERE max_gift_count IS NULL;
ALTER TABLE gift_policies ALTER COLUMN require_ekyc SET DEFAULT true;
ALTER TABLE gift_policies ALTER COLUMN require_ekyc SET NOT NULL;
ALTER TABLE gift_policies ALTER COLUMN max_gift_count SET DEFAULT 3;
ALTER TABLE gift_policies ALTER COLUMN max_gift_count SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_resale_policy_service_fee'
                   AND conrelid = 'resale_policies'::regclass) THEN
        ALTER TABLE resale_policies ADD CONSTRAINT ck_resale_policy_service_fee
            CHECK (service_fee_percent BETWEEN 0 AND 100);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_gift_policy_max_count'
                   AND conrelid = 'gift_policies'::regclass) THEN
        ALTER TABLE gift_policies ADD CONSTRAINT ck_gift_policy_max_count
            CHECK (max_gift_count BETWEEN 0 AND 100);
    END IF;
END $$;

COMMIT;
