-- Apply to ticket_db before deploying ticket-service.
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS gift_transfer_count integer NOT NULL DEFAULT 0
    CHECK (gift_transfer_count >= 0);

-- Preserve known gifts made before this counter was introduced; reruns never lower a counter.
UPDATE tickets t
SET gift_transfer_count = GREATEST(t.gift_transfer_count, history.gift_count)
FROM (
    SELECT ticket_id, COUNT(*)::integer AS gift_count
    FROM ticket_operations
    WHERE purpose = 'GIFT' AND state = 'TRANSFERRED'
    GROUP BY ticket_id
) history
WHERE t.id = history.ticket_id;
