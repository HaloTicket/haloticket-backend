-- Read-only audit of CHECK constraints on enum columns (generated from the Java enums; EnumCheckConstraintGuardTest keeps it complete).
-- Run against production or dev (psql meta-commands \c switch databases):
--   docker exec -i haloticket-postgres sh -c 'psql -U "$POSTGRES_USER" -d postgres -At -F " | "' < scripts/audit/enum_check_audit.sql
-- Output: one row per problem. Nothing printed under a database = every enum value is allowed by its constraint.
--   MISSING in CHECK: X   the code can write X, the database constraint would reject it  -> needs a migration
--   no CHECK constraint   the column has no constraint, nothing can be rejected (informational)
--   table not found       the table does not exist in that database yet

\echo === user_db
\c user_db
WITH expected(tbl, col, val) AS (VALUES
  ('auth_otp_challenges', 'purpose', 'REGISTRATION'),
  ('auth_otp_challenges', 'purpose', 'PASSWORD_RESET'),
  ('auth_otp_challenges', 'status', 'PENDING'),
  ('auth_otp_challenges', 'status', 'VERIFIED'),
  ('auth_otp_challenges', 'status', 'CONSUMED'),
  ('auth_otp_challenges', 'status', 'EXPIRED'),
  ('auth_otp_challenges', 'status', 'LOCKED'),
  ('ekyc_attempts', 'status', 'CREATED'),
  ('ekyc_attempts', 'status', 'PROCESSING'),
  ('ekyc_attempts', 'status', 'PASSED'),
  ('ekyc_attempts', 'status', 'FAILED'),
  ('ekyc_attempts', 'status', 'MANUAL_REVIEW'),
  ('ekyc_attempts', 'status', 'EXPIRED'),
  ('ekyc_attempts', 'status', 'ERROR'),
  ('ekyc_profiles', 'status', 'NOT_STARTED'),
  ('ekyc_profiles', 'status', 'PENDING'),
  ('ekyc_profiles', 'status', 'APPROVED'),
  ('ekyc_profiles', 'status', 'REJECTED'),
  ('ekyc_profiles', 'status', 'REQUIRES_UPDATE'),
  ('ekyc_profiles', 'status', 'REVOKED'),
  ('ekyc_profiles', 'status', 'EXPIRED'),
  ('users', 'status', 'ACTIVE'),
  ('users', 'status', 'INACTIVE'),
  ('users', 'status', 'BANNED')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === event_db
\c event_db
WITH expected(tbl, col, val) AS (VALUES
  ('event_categories', 'status', 'ACTIVE'),
  ('event_categories', 'status', 'INACTIVE'),
  ('event_organizers', 'role', 'OWNER'),
  ('event_organizers', 'role', 'CO_ORGANIZER'),
  ('event_organizers', 'role', 'MANAGER'),
  ('event_organizers', 'status', 'ACTIVE'),
  ('event_organizers', 'status', 'REVOKED'),
  ('events', 'event_approval_status', 'DRAFT'),
  ('events', 'event_approval_status', 'PENDING'),
  ('events', 'event_approval_status', 'APPROVED'),
  ('events', 'event_approval_status', 'REJECTED'),
  ('events', 'event_approval_status', 'UPDATE_PENDING'),
  ('events', 'event_status', 'DRAFT'),
  ('events', 'event_status', 'PENDING'),
  ('events', 'event_status', 'APPROVED'),
  ('events', 'event_status', 'LIVE'),
  ('events', 'event_status', 'COMPLETED'),
  ('events', 'event_status', 'CANCELLED'),
  ('events', 'tickets_sync_status', 'SYNCED'),
  ('events', 'tickets_sync_status', 'FAILED'),
  ('staff_assignments', 'role', 'OWNER'),
  ('staff_assignments', 'role', 'ADMIN'),
  ('staff_assignments', 'role', 'MANAGER'),
  ('staff_assignments', 'role', 'CHECKIN_STAFF'),
  ('staff_assignments', 'status', 'ACTIVE'),
  ('staff_assignments', 'status', 'REVOKED'),
  ('staff_assignments', 'status', 'PENDING'),
  ('staff_assignments', 'status', 'DECLINED'),
  ('vouchers', 'apply_scope', 'ALL'),
  ('vouchers', 'apply_scope', 'LIMITED_SHOWTIMES'),
  ('vouchers', 'discount_type', 'PERCENTAGE'),
  ('vouchers', 'discount_type', 'FIXED_AMOUNT'),
  ('vouchers', 'status', 'DRAFT'),
  ('vouchers', 'status', 'ACTIVE'),
  ('vouchers', 'status', 'INACTIVE'),
  ('vouchers', 'status', 'EXPIRED')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === ticket_db
\c ticket_db
WITH expected(tbl, col, val) AS (VALUES
  ('checkin_attempts', 'result', 'SUCCESS'),
  ('checkin_attempts', 'result', 'FAILED_SIGNATURE'),
  ('checkin_attempts', 'result', 'FAILED_ALREADY_IN'),
  ('checkin_attempts', 'result', 'OUTSIDE_CHECKIN_WINDOW'),
  ('checkin_attempts', 'result', 'TICKET_NOT_FOUND'),
  ('checkin_attempts', 'result', 'WRONG_EVENT'),
  ('checkin_attempts', 'result', 'WRONG_SHOWTIME'),
  ('checkin_attempts', 'result', 'TICKET_LOCKED'),
  ('checkin_attempts', 'result', 'WINDOW_UNVERIFIED'),
  ('checkins', 'status', 'VALID'),
  ('checkins', 'status', 'INVALID'),
  ('checkins', 'status', 'DUPLICATED'),
  ('outbox_events', 'status', 'PENDING'),
  ('outbox_events', 'status', 'PROCESSED'),
  ('outbox_events', 'status', 'FAILED'),
  ('resale_listings', 'status', 'LISTED'),
  ('resale_listings', 'status', 'SOLD'),
  ('resale_listings', 'status', 'CANCELLED'),
  ('seat_layouts', 'status', 'DRAFT'),
  ('seat_layouts', 'status', 'PUBLISHED'),
  ('seat_layouts', 'status', 'ARCHIVED'),
  ('seats', 'seat_status', 'AVAILABLE'),
  ('seats', 'seat_status', 'LOCKED'),
  ('seats', 'seat_status', 'SOLD'),
  ('seats', 'seat_status', 'BLOCKED'),
  ('seats', 'seat_type', 'REGULAR'),
  ('seats', 'seat_type', 'VIP'),
  ('seats', 'seat_type', 'ACCESSIBLE'),
  ('seats', 'seat_type', 'RESTRICTED_VIEW'),
  ('ticket_operations', 'state', 'REQUESTED'),
  ('ticket_operations', 'state', 'LOCKED'),
  ('ticket_operations', 'state', 'RELEASE_REQUESTED'),
  ('ticket_operations', 'state', 'RELEASED'),
  ('ticket_operations', 'state', 'TRANSFERRED'),
  ('ticket_operations', 'state', 'REJECTED'),
  ('ticket_operations', 'state', 'CANCELLED'),
  ('ticket_stock_reservations', 'status', 'HELD'),
  ('ticket_stock_reservations', 'status', 'CONSUMED'),
  ('ticket_stock_reservations', 'status', 'RELEASED'),
  ('ticket_stock_reservations', 'status', 'EXPIRED'),
  ('ticket_types', 'ticket_type_status', 'ACTIVE'),
  ('ticket_types', 'ticket_type_status', 'SOLD_OUT'),
  ('ticket_types', 'ticket_type_status', 'HIDDEN'),
  ('tickets', 'status', 'VALID'),
  ('tickets', 'status', 'USED'),
  ('tickets', 'status', 'TRANSFERED'),
  ('tickets', 'status', 'REVOKED'),
  ('tickets', 'status', 'LOCKED_FOR_RESALE'),
  ('tickets', 'status', 'LOCKED_FOR_TRANSFER')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === booking_db
\c booking_db
WITH expected(tbl, col, val) AS (VALUES
  ('bookings', 'status', 'PENDING'),
  ('bookings', 'status', 'PAID'),
  ('bookings', 'status', 'CONFIRMED'),
  ('bookings', 'status', 'CANCELLED'),
  ('bookings', 'status', 'EXPIRED'),
  ('bookings', 'status', 'REFUNDED'),
  ('outbox_events', 'status', 'PENDING'),
  ('outbox_events', 'status', 'PROCESSED'),
  ('outbox_events', 'status', 'FAILED'),
  ('payment_exceptions', 'kind', 'LATE_PAYMENT_NO_STOCK'),
  ('payment_exceptions', 'kind', 'LATE_PAYMENT_SEATS_UNAVAILABLE'),
  ('payment_exceptions', 'kind', 'LATE_PAYMENT_UNEXPECTED_STATUS'),
  ('payment_exceptions', 'kind', 'ISSUANCE_FAILED'),
  ('payment_exceptions', 'status', 'OPEN'),
  ('payment_exceptions', 'status', 'RESOLVED'),
  ('payment_exceptions', 'status', 'REFUNDED_MANUALLY'),
  ('seat_reservations', 'status', 'HOLDING'),
  ('seat_reservations', 'status', 'CONFIRMED'),
  ('seat_reservations', 'status', 'SOLD'),
  ('seat_reservations', 'status', 'RELEASED'),
  ('seat_reservations', 'status', 'CANCELLED')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === payment_db
\c payment_db
WITH expected(tbl, col, val) AS (VALUES
  ('organizer_payout_accounts', 'verification_status', 'UNVERIFIED'),
  ('organizer_payout_accounts', 'verification_status', 'PENDING'),
  ('organizer_payout_accounts', 'verification_status', 'VERIFIED'),
  ('organizer_payout_accounts', 'verification_status', 'REJECTED'),
  ('organizer_payout_accounts', 'verification_status', 'REVOKED'),
  ('outbox_events', 'status', 'PENDING'),
  ('outbox_events', 'status', 'PROCESSED'),
  ('outbox_events', 'status', 'FAILED'),
  ('payment_transactions', 'status', 'SUCCESS'),
  ('payment_transactions', 'status', 'FAILED'),
  ('payment_transactions', 'status', 'PENDING_VERIFICATION'),
  ('payments', 'method', 'VNPAY'),
  ('payments', 'method', 'VIETQR'),
  ('payments', 'method', 'MOMO'),
  ('payments', 'method', 'ZALOPAY'),
  ('payments', 'method', 'SHOPEEPAY'),
  ('payments', 'provider', 'VNPAY_GATEWAY'),
  ('payments', 'provider', 'ZALOPAY_GATEWAY'),
  ('payments', 'provider', 'STRIPE'),
  ('payments', 'provider', 'SEPAY'),
  ('payments', 'provider', 'MOMO_GATEWAY'),
  ('payments', 'provider', 'SHOPEEPAY_GATEWAY'),
  ('payments', 'status', 'PENDING'),
  ('payments', 'status', 'SUCCESS'),
  ('payments', 'status', 'FAILED'),
  ('payments', 'status', 'REFUNDED'),
  ('payouts', 'status', 'PENDING'),
  ('payouts', 'status', 'PROCESSING'),
  ('payouts', 'status', 'COMPLETED'),
  ('payouts', 'status', 'FAILED'),
  ('payouts', 'status', 'DISPUTE'),
  ('refunds', 'status', 'PENDING'),
  ('refunds', 'status', 'PROCESSING'),
  ('refunds', 'status', 'COMPLETED'),
  ('refunds', 'status', 'FAILED')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === notification_db
\c notification_db
WITH expected(tbl, col, val) AS (VALUES
  ('notification_templates', 'channel', 'EMAIL'),
  ('notification_templates', 'channel', 'SMS'),
  ('notification_templates', 'channel', 'PUSH'),
  ('notification_templates', 'status', 'ACTIVE'),
  ('notification_templates', 'status', 'INACTIVE'),
  ('notification_templates', 'type', 'TICKET_CONFIRMATION'),
  ('notification_templates', 'type', 'EVENT_REMINDER'),
  ('notification_templates', 'type', 'SYSTEM_ALERT'),
  ('notification_templates', 'type', 'EKYC_RESULT'),
  ('notification_templates', 'type', 'EVENT_MEMBER_INVITATION'),
  ('notifications', 'channel', 'EMAIL'),
  ('notifications', 'channel', 'SMS'),
  ('notifications', 'channel', 'PUSH'),
  ('notifications', 'status', 'SENT'),
  ('notifications', 'status', 'FAILED'),
  ('notifications', 'status', 'PENDING')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;

\echo === marketplace_db
\c marketplace_db
WITH expected(tbl, col, val) AS (VALUES
  ('marketplace_sagas', 'state', 'WAITING_LOCK'),
  ('marketplace_sagas', 'state', 'WAITING_RECIPIENT'),
  ('marketplace_sagas', 'state', 'WAITING_TRANSFER'),
  ('marketplace_sagas', 'state', 'COMPLETED'),
  ('marketplace_sagas', 'state', 'FAILED'),
  ('marketplace_sagas', 'state', 'COMPENSATING'),
  ('marketplace_sagas', 'state', 'COMPENSATED'),
  ('marketplace_transactions', 'status', 'PENDING_PAYMENT'),
  ('marketplace_transactions', 'status', 'PAYMENT_PROCESSING'),
  ('marketplace_transactions', 'status', 'TRANSFERRING'),
  ('marketplace_transactions', 'status', 'COMPLETED'),
  ('marketplace_transactions', 'status', 'REFUND_PENDING'),
  ('marketplace_transactions', 'status', 'REFUNDED'),
  ('marketplace_transactions', 'status', 'CANCELLED'),
  ('marketplace_transactions', 'status', 'EXPIRED'),
  ('outbox_events', 'status', 'PENDING'),
  ('outbox_events', 'status', 'PROCESSED'),
  ('outbox_events', 'status', 'FAILED'),
  ('seller_settlements', 'status', 'PENDING_SETTLEMENT'),
  ('seller_settlements', 'status', 'SETTLED'),
  ('seller_settlements', 'status', 'CANCELLED'),
  ('ticket_listings', 'status', 'ACTIVE'),
  ('ticket_listings', 'status', 'RESERVED'),
  ('ticket_listings', 'status', 'SOLD'),
  ('ticket_listings', 'status', 'CANCELLED'),
  ('ticket_listings', 'status', 'EXPIRED'),
  ('ticket_listings', 'status', 'PAUSED_BY_EVENT'),
  ('ticket_listings', 'status', 'PENDING_APPROVAL'),
  ('ticket_transfers', 'status', 'PENDING'),
  ('ticket_transfers', 'status', 'COMPLETED'),
  ('ticket_transfers', 'status', 'CANCELLED'),
  ('ticket_transfers', 'type', 'GIFT'),
  ('ticket_transfers', 'type', 'RESALE')
),
found AS (
  SELECT e.tbl, e.col, e.val, to_regclass('public.' || e.tbl) AS rel,
         (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
           WHERE c.contype = 'c' AND c.conrelid = to_regclass('public.' || e.tbl)
             AND position('(' || e.col || ')' IN pg_get_constraintdef(c.oid)) > 0 LIMIT 1) AS def
  FROM expected e
)
SELECT DISTINCT tbl AS table_name, col AS column_name,
       CASE WHEN rel IS NULL THEN 'table not found'
            WHEN def IS NULL THEN 'no CHECK constraint'
            ELSE 'MISSING in CHECK: ' || val END AS finding
FROM found
WHERE rel IS NULL OR def IS NULL OR position('''' || val || '''' IN def) = 0
ORDER BY 1, 2, 3;
