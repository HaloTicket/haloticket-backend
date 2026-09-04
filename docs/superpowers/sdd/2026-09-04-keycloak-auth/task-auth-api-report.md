# Auth API boundary report

## Delivered

- Added `AuthController` mappings for registration, registration OTP verification/resend, password recovery start/verify/resend/reset, and login.
- Added request DTO records with controller-level validation for email, password policy, required identifiers, and idempotency keys.
- Added typed JSON error mapping for OTP lifecycle conflicts, cooldown/rate limits, invalid state, delivery/provider failures, and invalid requests.
- OTP responses expose only challenge metadata (`challengeId`, `expiresAt`, `resendAvailableAt`); plaintext OTP is never serialized.
- Added `OtpDeliveryPort` and `KeycloakAuthPort` boundaries. Default providers fail closed with HTTP 503 until SMTP/notification and Keycloak Admin/token adapters are configured.
- Resend derives the destination email from the persisted challenge subject, not from client input.

## Verification

- TDD RED test added at `user-service/src/test/java/com/haloticket/user_service/controller/AuthControllerTest.java`.
- Maven wrapper could not start in this environment: `Cannot start maven from wrapper` / PowerShell `Cannot index into a null array`.
- `git diff --check` passed before commit.
- Compile and full user-service test execution remain unverified because Maven did not start; no pass count is claimed.

## Configuration contract

- Configure `AUTH_OTP_PEPPER` (production fail-fast remains unchanged).
- Provide a real `OtpDeliveryPort` implementation backed by the notification/SMTP provider before enabling registration or password recovery in a deployed environment.
- Provide a real `KeycloakAuthPort` implementation using the configured confidential `user-service` client before enabling registration, password reset, or login.

