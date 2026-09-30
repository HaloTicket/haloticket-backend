-- Apply to marketplace_db before deploying the batch marketplace service.
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'ticket_listings' AND con.contype = 'u'
          AND pg_get_constraintdef(con.oid) = 'UNIQUE (ticket_id)'
    LOOP
        EXECUTE format('ALTER TABLE ticket_listings DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE marketplace_transactions ADD COLUMN IF NOT EXISTS selection_explicit boolean;

CREATE TABLE IF NOT EXISTS ticket_listing_members (
    listing_id varchar(255) NOT NULL REFERENCES ticket_listings(id),
    ticket_id varchar(255) NOT NULL,
    ticket_type_id varchar(255), showtime_id varchar(255), ticket_type_name varchar(255),
    section_name varchar(255), row_name varchar(255), seat_name varchar(255),
    original_price numeric(19,0), sold boolean NOT NULL DEFAULT false,
    CONSTRAINT uk_listing_member UNIQUE (listing_id, ticket_id)
);
CREATE TABLE IF NOT EXISTS ticket_transfer_members (
    transfer_id varchar(255) NOT NULL REFERENCES ticket_transfers(id),
    ticket_id varchar(255) NOT NULL,
    ticket_type_id varchar(255), showtime_id varchar(255), ticket_type_name varchar(255),
    section_name varchar(255), row_name varchar(255), seat_name varchar(255),
    original_price numeric(19,0), sold boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS marketplace_transaction_tickets (
    transaction_id varchar(255) NOT NULL REFERENCES marketplace_transactions(id),
    ticket_id varchar(255) NOT NULL,
    CONSTRAINT uk_transaction_ticket UNIQUE (transaction_id, ticket_id)
);

INSERT INTO ticket_listing_members (listing_id, ticket_id, ticket_type_name, section_name, row_name, seat_name, original_price, sold)
SELECT id, ticket_id, ticket_type_name_snapshot, section_snapshot, row_snapshot, seat_snapshot, original_price,
       status = 'SOLD' FROM ticket_listings
ON CONFLICT (listing_id, ticket_id) DO NOTHING;
INSERT INTO marketplace_transaction_tickets (transaction_id, ticket_id)
SELECT t.id, l.ticket_id FROM marketplace_transactions t JOIN ticket_listings l ON l.id = t.ticket_listing_id
ON CONFLICT (transaction_id, ticket_id) DO NOTHING;
INSERT INTO ticket_transfer_members (transfer_id, ticket_id, ticket_type_name)
SELECT id, ticket_id, ticket_type_name FROM ticket_transfers
WHERE NOT EXISTS (SELECT 1 FROM ticket_transfer_members m WHERE m.transfer_id = ticket_transfers.id);
