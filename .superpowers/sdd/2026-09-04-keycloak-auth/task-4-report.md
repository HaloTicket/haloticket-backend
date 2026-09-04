# Task 4 report: OTP challenge domain

## Scope

Implemented the shared JPA OTP challenge domain for registration and password reset. No controller, email provider, gateway route, or `.gitignore` changes were made.

The checked-in `task-4-brief.md` still describes the older current-user controller task; this implementation follows the newer Task 4 request for the OTP domain. The existing ledger ruling says JPA `ddl-auto: update` is the migration strategy until a migration runner is established, so `AuthOtpChallenge` is the schema source for this task.

## Implementation

- `AuthOtpChallenge` maps `auth_otp_challenges` with purpose/status, subject binding, idempotency key, HMAC hash, expiry, resend cooldown, send window/count, failed attempts, request-IP hash, timestamps, and optimistic `@Version` locking.
- `OtpGenerator` uses `SecureRandom` and emits exactly six digits.
- `OtpHasher` stores/verifies only HMAC-SHA-256 digests using a server-side pepper; plaintext OTP is returned only in the in-memory service result for the email adapter in a later task.
- `AuthOtpChallengeService` contract and implementation support idempotent create, resend, verify, and consume operations.
- Lifecycle is `PENDING -> VERIFIED -> CONSUMED`; invalid/expired challenges become `EXPIRED` or `LOCKED` and cannot be reused.
- Defaults: five-minute TTL, 60-second resend cooldown, five sends per challenge window, five failed attempts.
- Bean configuration uses `AUTH_OTP_PEPPER`/`auth.otp.pepper`; the development fallback must be replaced by a secret in deployed environments.

Global email/IP quotas across separate challenges are intentionally left behind the rate-limit/API layer in the later registration/reset tasks; the durable per-challenge send and attempt controls are implemented here.

## TDD evidence

- RED: initial `OtpDomainTest` run failed at test compilation because the requested domain types did not exist. The Maven wrapper first also failed to start in the local sandbox; an approved Maven run then confirmed the expected missing-type failure.
- GREEN focused: `cmd /c mvnw.cmd test -Dtest=OtpDomainTest` -> 7 tests, 0 failures, 0 errors.
- Full suite: `cmd /c mvnw.cmd test` -> 21 tests, 0 failures, 0 errors. Spring context also verified JPA schema creation for `auth_otp_challenges` and its unique idempotency binding constraint.

## Known follow-up

The service contract deliberately does not send mail and does not expose HTTP. Registration/reset tasks will consume the plaintext result in bounded memory, wire the email sender, and add Redis-backed email/IP quotas across separate challenges. This task enforces all durable challenge-local controls required before those adapters exist.
