-- Apply to booking_db by hand, before or with the booking-service that records payment exceptions.
--
-- payment_exceptions holds payments that were taken but the order could not be completed (late payment after the booking was
-- cancelled or expired, tickets that could not be issued). An admin refunds on the gateway by hand and marks the row.
-- Hibernate (ddl-auto: update) also creates the table from the PaymentException entity; this script adds what Hibernate does
-- not: the partial unique index for rows without a payment id, and CHECK constraints equal to the enums.
-- Safe to run more than once. Take a pg_dump of booking_db first.
--
-- When you add a value to PaymentExceptionKind or PaymentExceptionStatus, add it to the CHECK lists below (a test fails if you
-- forget).
--   kind   : LATE_PAYMENT_NO_STOCK, LATE_PAYMENT_SEATS_UNAVAILABLE, LATE_PAYMENT_UNEXPECTED_STATUS, ISSUANCE_FAILED
--   status : OPEN, RESOLVED, REFUNDED_MANUALLY

BEGIN;

CREATE TABLE IF NOT EXISTS payment_exceptions (
    id              varchar(255) PRIMARY KEY,
    booking_id      varchar(255) NOT NULL,
    payment_id      varchar(255),
    kind            varchar(40)  NOT NULL,
    amount          double precision,
    provider        varchar(32),
    status          varchar(24)  NOT NULL,
    detail          varchar(500),
    created_at      timestamp,
    resolved_at     timestamp,
    resolved_by     varchar(64),
    resolution_note varchar(500)
);

-- Hibernate may have created the table first (with its own enum CHECKs, or none): replace them with ours.
ALTER TABLE payment_exceptions DROP CONSTRAINT IF EXISTS payment_exceptions_kind_check;
ALTER TABLE payment_exceptions
    ADD CONSTRAINT payment_exceptions_kind_check
    CHECK (kind IN ('LATE_PAYMENT_NO_STOCK', 'LATE_PAYMENT_SEATS_UNAVAILABLE', 'LATE_PAYMENT_UNEXPECTED_STATUS', 'ISSUANCE_FAILED'));

ALTER TABLE payment_exceptions DROP CONSTRAINT IF EXISTS payment_exceptions_status_check;
ALTER TABLE payment_exceptions
    ADD CONSTRAINT payment_exceptions_status_check
    CHECK (status IN ('OPEN', 'RESOLVED', 'REFUNDED_MANUALLY'));

-- One row per (payment, kind): a redelivered Kafka message creates nothing. A row with a payment id is covered here;
-- Hibernate's own uk_payment_exceptions_payment_kind constraint (payment_id, kind) does the same and may already exist.
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_exceptions_payment_kind_idx
    ON payment_exceptions (payment_id, kind) WHERE payment_id IS NOT NULL;

-- ISSUANCE_FAILED has no payment id: one row per (booking, kind).
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_exceptions_booking_kind_nopayment
    ON payment_exceptions (booking_id, kind) WHERE payment_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_exceptions_status_created
    ON payment_exceptions (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_exceptions_booking
    ON payment_exceptions (booking_id);

COMMIT;

-- VERIFY (read only; run after the migration). Expect the two CHECK constraints and the indexes.
SELECT conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.payment_exceptions'::regclass AND contype = 'c';

SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'payment_exceptions' ORDER BY indexname;

-- ROLLBACK (only if needed, and only while the table holds nothing an admin still has to handle):
--   BEGIN;
--   DROP TABLE IF EXISTS payment_exceptions;
--   COMMIT;
