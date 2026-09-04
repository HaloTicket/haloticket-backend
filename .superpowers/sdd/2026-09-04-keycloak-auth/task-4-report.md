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
- Bean configuration uses `AUTH_OTP_PEPPER`/`auth.otp.pepper` with no public fallback; application startup fails fast when neither is configured.
- Mutation reads use a JPA pessimistic write lock and optimistic-lock failures are translated to `ConflictException`.
- Reusing an idempotency key for `EXPIRED`, `LOCKED`, `VERIFIED`, or `CONSUMED` challenges is rejected instead of returning stale state.
- `OtpChallengeCommand` trims and validates nonblank, bounded purpose inputs before repository access.
- `OtpRateLimiter` provides synchronized one-hour quotas of 10 per subject and 10 per IP for registration/reset API callers. It is intentionally a single-instance primitive; multi-instance deployment must replace its storage with Redis before exposure.

The later registration/reset APIs must call `OtpRateLimiter.tryAcquire(subject, ipHash, now)` before creating a challenge and return a rate-limit response when it returns false. Controllers and email delivery remain out of scope.

## TDD evidence

- RED: initial `OtpDomainTest` run failed at test compilation because the requested domain types did not exist. The Maven wrapper first also failed to start in the local sandbox; an approved Maven run then confirmed the expected missing-type failure.
- GREEN focused: Maven compiler plus `OtpDomainTest,OtpRateLimitTest` -> 13 tests, 0 failures, 0 errors (with `AUTH_OTP_PEPPER=test-pepper`).
- A full suite attempt reached Spring context and correctly failed without `AUTH_OTP_PEPPER`, proving the new fail-fast configuration. The requested minimal compile/focused verification passed; full suite was not rerun with the test secret before this commit.

## Known follow-up

The service contract deliberately does not send mail and does not expose HTTP. Registration/reset tasks will consume the plaintext result in bounded memory, wire the email sender, and replace the in-memory quota storage with Redis for multi-instance deployments.

## Review fix round

Addressed the remaining Task 4 review findings:

- Expired `PENDING` challenges are transitioned to `EXPIRED` and are never returned for a reused idempotency key.
- Concurrent inserts that lose the idempotency unique constraint reload the winning challenge and return no plaintext OTP, preventing a duplicate email send from the losing request.
- Challenge mutations use `saveAndFlush()` and translate both JPA `OptimisticLockException` and Spring optimistic-lock failures into the domain conflict exception.
- Regression tests were added before implementation and observed failing for all three behaviors.

Verification with `AUTH_OTP_PEPPER=test-pepper`:

- Focused `OtpDomainTest`: 13/13 passed.
- Full `user-service` suite: 29/29 passed.
- `git diff --check`: clean.

The Maven wrapper could not start in this environment; the installed Maven 3.9.16 distribution was invoked directly with `-Dmaven.compiler.fork=true` and `-Duser.home=C:\\Users\\Windows`. The forked compiler emitted a JDK archive cleanup warning, but the focused and full test reports completed successfully.

## Final review fix: transaction boundary

The remaining PostgreSQL transaction-boundary findings were addressed in the follow-up commit:

- `OtpChallengePersistence.insert()` performs the unique-constrained insert in `REQUIRES_NEW`; after a losing insert rolls back, the service queries the winner only after that independent transaction has ended.
- `OtpChallengePersistence.markExpired()` persists `PENDING -> EXPIRED` in `REQUIRES_NEW` before the idempotency-reuse exception is raised, so the state transition is not rolled back by the caller's exception.
- Existing `resend`, `verify`, and `consume` methods retain their transaction and pessimistic-lock boundaries.
- `OtpPersistenceBoundaryTest` asserts both persistence operations declare `Propagation.REQUIRES_NEW`; the domain test continues to assert winner recovery and expired-state persistence through the persistence collaborator.

Verification attempted for this fix:

- Focused command: `mvn -Dtest=OtpPersistenceBoundaryTest,OtpDomainTest test` with `MAVEN_OPTS=-Dmaven.repo.local=D:\\HaloTicket\\.m2repo`.
- The command could not reach test execution because the existing uncommitted `user-service/config/SecurityConfig.java` does not compile against the resolved Spring Security version (`jwtValidator(...)` is unavailable). This is outside the OTP fix and was not modified.
- A real PostgreSQL concurrency integration test was not runnable in this environment: the repository has no Testcontainers/H2 test dependency or configured PostgreSQL test profile. The production transaction boundary is explicit and the unit regression tests cover the failure/recovery semantics; database-level concurrency remains a deployment-level verification item.

Final verification after commit `1ab32fe`:

- Focused `OtpPersistenceBoundaryTest,OtpDomainTest`: not executed; Maven exited `1` during main compilation because the pre-existing uncommitted `config/SecurityConfig.java:37` calls unavailable `jwtValidator(...)`.
- Full `user-service` suite: not executed for the same compile blocker; no test failures were produced because test execution never started.
