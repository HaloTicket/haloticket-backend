-- Keep payout approval distinct from an actual transfer and distinguish rejection
-- from a failed transfer. Existing rows retain their current status.
ALTER TABLE payouts DROP CONSTRAINT IF EXISTS payouts_status_check;

ALTER TABLE payouts
    ADD CONSTRAINT payouts_status_check
    CHECK (status IN (
        'PENDING',
        'APPROVED',
        'PROCESSING',
        'COMPLETED',
        'FAILED',
        'REJECTED',
        'CANCELLED',
        'NEEDS_VERIFICATION',
        'DISPUTE'
    ));
