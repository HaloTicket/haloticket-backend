-- Apply to marketplace_db before deploying marketplace-service.
ALTER TABLE ticket_listings
  ADD COLUMN IF NOT EXISTS service_fee_percent numeric(7,3) NOT NULL DEFAULT 5,
  ADD COLUMN IF NOT EXISTS moderation_reason varchar(1000),
  ADD COLUMN IF NOT EXISTS moderated_by varchar(255),
  ADD COLUMN IF NOT EXISTS moderated_at timestamptz;
ALTER TABLE ticket_transfers
  ADD COLUMN IF NOT EXISTS require_ekyc boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS max_gift_count integer NOT NULL DEFAULT 3,
  ADD COLUMN IF NOT EXISTS recipient_ekyc_verified boolean;
DO $$
DECLARE status_constraint record;
BEGIN
  FOR status_constraint IN SELECT conname FROM pg_constraint
    WHERE conrelid = 'ticket_listings'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) ~ '\mstatus\M'
  LOOP
    EXECUTE format('ALTER TABLE ticket_listings DROP CONSTRAINT %I', status_constraint.conname);
  END LOOP;
  ALTER TABLE ticket_listings ADD CONSTRAINT ticket_listings_status_check
    CHECK (status IN ('ACTIVE','RESERVED','SOLD','CANCELLED','EXPIRED','PAUSED_BY_EVENT','PENDING_APPROVAL'));
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'ticket_listings'::regclass AND conname = 'ticket_listing_service_fee_range') THEN
    ALTER TABLE ticket_listings ADD CONSTRAINT ticket_listing_service_fee_range CHECK (service_fee_percent BETWEEN 0 AND 100);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'ticket_transfers'::regclass AND conname = 'ticket_transfer_gift_limit_range') THEN
    ALTER TABLE ticket_transfers ADD CONSTRAINT ticket_transfer_gift_limit_range CHECK (max_gift_count BETWEEN 0 AND 100);
  END IF;
END $$;
