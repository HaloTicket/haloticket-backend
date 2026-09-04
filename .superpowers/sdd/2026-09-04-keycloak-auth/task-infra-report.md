# Infrastructure lane report

Date: 2026-09-04
Branch: `feature/keycloak-auth`

## Scope

This lane changes only Keycloak/compose configuration and JWT security at the gateway and user-service boundary. It does not modify OTP implementation/tests or the web repository.

## Changes

- Disabled Keycloak native registration and native password reset in `haloticket-infrastructure/keycloak/realm-export.json`; backend challenge APIs remain the owner of those flows.
- Enabled Keycloak brute-force protection.
- Added `api-gateway` and `user-service` audience mappers to the web and mobile clients.
- Added a gateway route forwarding `/api/auth/**` to `USER-SERVICE`.
- Allowed `/api/auth/**` through the user-service security boundary so the public challenge controllers can receive requests from the gateway.
- Added issuer-aware audience validation for `api-gateway` and `user-service` resource servers.
- Removed insecure default Keycloak admin credentials from both compose files; deployment must provide `KEYCLOAK_ADMIN` and `KEYCLOAK_ADMIN_PASSWORD`.
- Added unit coverage for accepted and rejected audiences in both services.

## Verification

- Realm export parsed successfully with PowerShell `ConvertFrom-Json`.
- `git diff --check` passed.
- Maven wrapper is broken in this environment (`Cannot start maven from wrapper`). A direct Maven run was started with an isolated local repository and is downloading dependencies; final output is recorded in the task transcript if it completes.

## Explicit follow-ups outside this lane

- `KEYCLOAK_USER_SERVICE_SECRET` must be generated, stored in a secret manager, and wired into the future Keycloak Admin API adapter. The existing realm export contains a legacy client secret and should be rotated before production; this lane does not invent or publish a replacement secret.
- SMTP credentials/server and an email sender are required for OTP delivery; no SMTP sink or production mail configuration exists here.
- OTP global email/IP quotas need Redis-backed production configuration; this lane does not alter OTP service code.
- Production Keycloak must run with TLS/hostname configuration and a non-development command. Local compose still supports the existing developer setup; production deployment must set `KEYCLOAK_COMMAND`, hostname, proxy, and TLS-related secrets according to the deployment platform.
- Realm import is not a migration for an already-running realm. Apply equivalent settings through Keycloak Admin/API or recreate the development realm when testing the export.
