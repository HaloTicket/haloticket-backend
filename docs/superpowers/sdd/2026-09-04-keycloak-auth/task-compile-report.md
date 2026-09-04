# Task compile report — Keycloak audience validator regression

## Scope

Fix the Spring Security 7.1.1 API regression introduced by audience validation changes. Preserve issuer and audience validation in both user-service and api-gateway, and leave OTP behavior unchanged.

## Changes

- `user-service` now exposes a `NimbusJwtDecoder` bean, configures the default issuer validator plus the `user-service` audience validator on that decoder, and supplies it through the servlet resource-server JWT configuration.
- `api-gateway` now exposes a `NimbusReactiveJwtDecoder` bean, configures the default issuer validator plus the `api-gateway` audience validator on that decoder, and supplies it through the reactive resource-server JWT configuration.
- Added a focused user-service regression test that guards the decoder bean contract.
- Added a reactive decoder mock to the existing gateway security test so the test context does not perform Keycloak discovery.

## Verification

Commands used with the checked-in Maven distribution and the existing local repository:

- user-service focused regression: `SecurityConfigTest` — 1/1 passed
- user-service full test suite with `AUTH_OTP_PEPPER=test-pepper` — 34/34 passed
- api-gateway full test suite — 4/4 passed
- `git diff --check` — passed

The Maven wrapper script itself still reports `Cannot start maven from wrapper` in this Windows environment; the equivalent Maven distribution under `.m2/wrapper/dists` was used for verification.
