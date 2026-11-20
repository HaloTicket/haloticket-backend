-- Run on ticket_db. Older schemas require version even though ticket-service
-- no longer maps this legacy column when inserting issued tickets.
ALTER TABLE tickets ALTER COLUMN version SET DEFAULT 0;
