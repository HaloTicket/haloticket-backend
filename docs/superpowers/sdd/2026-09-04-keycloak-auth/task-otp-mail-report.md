# OTP email delivery implementation report

## Scope

Replaced the default OTP delivery provider's unconditional 503 behavior with an SMTP adapter that is enabled only when `AUTH_OTP_DELIVERY=smtp`. The default fail-closed provider remains active when SMTP delivery is disabled, so tests and deployments without mail configuration cannot report false success.

## Implementation

- Added `spring-boot-starter-mail` to `user-service`.
- Added `SmtpOtpDeliveryAdapter`, which sends a plain text verification email containing the six-digit code, purpose, and expiry time.
- Added `SmtpMailConfiguration` with conditional SMTP beans and fail-fast validation for `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM`.
- Added `spring.mail` and `auth.otp.mail` environment-backed configuration.
- Added `@ConditionalOnMissingBean` to the default provider so an enabled SMTP adapter takes precedence.
- No plaintext OTP is logged or persisted by the adapter.

## Configuration

Set these environment variables for real delivery:

```text
AUTH_OTP_DELIVERY=smtp
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=...
SMTP_PASSWORD=...
SMTP_FROM=no-reply@example.com
```

Optional: `SMTP_AUTH`, `SMTP_STARTTLS`, and `AUTH_OTP_EXPIRY_MINUTES`.

## TDD and verification

- RED: new adapter/config tests initially failed because the production adapter/config did not exist; the first Maven attempt was blocked by the wrapper/cache environment.
- GREEN: focused SMTP tests `2/2` passed.
- Full `user-service` tests `39/39` passed.
- `git diff --check` is required before commit.

## Known limitation

No live SMTP delivery was attempted in tests; configure a real SMTP relay and restart `user-service` to send production email.
