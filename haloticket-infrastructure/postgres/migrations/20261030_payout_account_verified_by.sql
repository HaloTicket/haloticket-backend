ALTER TABLE organizer_payout_accounts
    ADD COLUMN IF NOT EXISTS verified_by VARCHAR(255);
