# Task 3 Report: Backend user sync service

## Scope

- Added `UserProfileUpdateRequest` for editable profile fields.
- Added `keycloakId` to `UserResponse`.
- Extended `UserService` with current-user sync and profile update operations.
- Implemented `createUser` and `getUserByKeycloakId` according to the ledger ruling.
- Implemented JWT-to-user synchronization with Keycloak subject/email identity checks, application role mapping, active defaults, and last-login tracking.
- Implemented current-user profile updates limited to full name, phone number, gender, and avatar URL.
- Added `GET /api/users/me` and `PUT /api/users/me`; both use the authenticated `Jwt` and never accept a caller-supplied identity.
- Removed the legacy `POST /api/users` and `GET /api/users/{keycloakId}` controller mappings. The service methods remain available for internal/bootstrap callers, but neither operation is exposed over the authenticated public HTTP surface, eliminating the arbitrary identity/role escalation path.
- Added controlled `409 CONFLICT` handling when a sync email is owned by another user, including the case where the Keycloak subject already exists with a different email.

## TDD evidence

- RED: focused `UserControllerTest,UserServiceImplTest` failed with 4 expected behavior failures: `/me` was routed to the legacy `{keycloakId}` mapping, PUT was unsupported, and both legacy endpoints were still exposed. The new existing-subject email-collision test was also written before the production check.
- GREEN: `./mvnw -Dtest=UserServiceImplTest,UserControllerTest test` passed with 10 tests, 0 failures, 0 errors, and 0 skipped.
- Full suite: `./mvnw test` passed with 13 tests, 0 failures, 0 errors, and 0 skipped.

The Windows Maven wrapper was invoked through Git Bash with elevated filesystem access because direct PowerShell startup failed with `Cannot start maven from wrapper`; the full suite also connected to the configured local PostgreSQL test database successfully.

## Commit

The original implementation is committed as `feat: sync keycloak users into user database`. This review fix is committed separately after the verification above.
