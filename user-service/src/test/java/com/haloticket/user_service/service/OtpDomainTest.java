package com.haloticket.user_service.service;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.enums.OtpStatus;
import com.haloticket.user_service.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.service.impl.OtpChallengeServiceImpl;
import com.haloticket.user_service.config.AuthOtpConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OtpDomainTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void generatedOtpIsSixDigitsAndHashIsNotPlaintext() {
        OtpGenerator generator = new OtpGenerator();
        String otp = generator.generate();
        OtpHasher hasher = new OtpHasher("test-pepper");

        assertThat(otp).matches("\\d{6}");
        assertThat(hasher.hash(otp)).doesNotContain(otp);
        assertThat(hasher.matches(otp, hasher.hash(otp))).isTrue();
    }

    @Test
    void blankConfiguredPepperIsRejected() {
        assertThatThrownBy(() -> new AuthOtpConfiguration().otpHasher(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsPendingChallengeWithFiveMinuteTtlAndIdempotencyBinding() {
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdempotencyKeyAndPurposeAndSubject("request-1", OtpPurpose.REGISTRATION, "user@example.com"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthOtpChallengeService service = service(repository);

        var result = service.create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, "user@example.com", "request-1", "ip-hash"));

        assertThat(result.challenge().getStatus()).isEqualTo(OtpStatus.PENDING);
        assertThat(result.challenge().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.plaintextOtp()).matches("\\d{6}");
        assertThat(result.challenge().getOtpHash()).doesNotContain(result.plaintextOtp());
        assertThat(result.challenge().getResendAvailableAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void idempotentCreateReturnsExistingChallengeWithoutGeneratingAnotherOtp() {
        AuthOtpChallenge existing = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.PASSWORD_RESET).subject("user@example.com")
                .idempotencyKey("request-1").status(OtpStatus.PENDING)
                .expiresAt(NOW.plusSeconds(300)).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdempotencyKeyAndPurposeAndSubject("request-1", OtpPurpose.PASSWORD_RESET, "user@example.com"))
                .thenReturn(Optional.of(existing));
        AuthOtpChallengeService service = service(repository);

        var result = service.create(new OtpChallengeCommand(
                OtpPurpose.PASSWORD_RESET, "user@example.com", "request-1", "ip-hash"));

        assertThat(result.challenge()).isSameAs(existing);
        assertThat(result.plaintextOtp()).isNull();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void expiredPendingChallengeIsNotReturnedForReusedIdempotencyKey() {
        AuthOtpChallenge existing = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .idempotencyKey("request-1").status(OtpStatus.PENDING)
                .expiresAt(NOW.minusSeconds(1)).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdempotencyKeyAndPurposeAndSubject("request-1", OtpPurpose.REGISTRATION, "user@example.com"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service(repository).create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, "user@example.com", "request-1", "ip-hash")))
                .isInstanceOf(AuthOtpChallengeService.IdempotencyKeyReuseException.class);
        assertThat(existing.getStatus()).isEqualTo(OtpStatus.EXPIRED);
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void concurrentCreateLosingUniqueInsertReturnsWinningChallengeWithoutOtp() {
        AuthOtpChallenge winner = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .idempotencyKey("request-1").status(OtpStatus.PENDING)
                .expiresAt(NOW.plusSeconds(300)).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdempotencyKeyAndPurposeAndSubject("request-1", OtpPurpose.REGISTRATION, "user@example.com"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        var result = service(repository).create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, "user@example.com", "request-1", "ip-hash"));

        assertThat(result.challenge()).isSameAs(winner);
        assertThat(result.plaintextOtp()).isNull();
    }

    @Test
    void reusedIdempotencyKeyCannotReturnTerminalChallenge() {
        AuthOtpChallenge existing = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.PASSWORD_RESET).subject("user@example.com")
                .idempotencyKey("request-1").status(OtpStatus.EXPIRED).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdempotencyKeyAndPurposeAndSubject("request-1", OtpPurpose.PASSWORD_RESET, "user@example.com"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service(repository).create(new OtpChallengeCommand(
                OtpPurpose.PASSWORD_RESET, "user@example.com", "request-1", "ip-hash")))
                .isInstanceOf(AuthOtpChallengeService.IdempotencyKeyReuseException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBlankOrOversizedCommandBeforeRepositoryAccess() {
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        AuthOtpChallengeService service = service(repository);

        assertThatThrownBy(() -> service.create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, "  ", "request-1", "ip-hash")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, "user@example.com", "x".repeat(129), "ip-hash")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void verifyRejectsWrongOtpLocksAfterFiveAttemptsAndCannotBeReused() {
        AuthOtpChallenge challenge = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .otpHash(new OtpHasher("test-pepper").hash("123456"))
                .status(OtpStatus.PENDING).expiresAt(NOW.plusSeconds(300))
                .failedAttempts(0).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdForUpdate("challenge-1")).thenReturn(Optional.of(challenge));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthOtpChallengeService service = service(repository);

        for (int attempt = 1; attempt <= 5; attempt++) {
            var result = service.verify("challenge-1", "000000", NOW);
            assertThat(result.accepted()).isFalse();
        }
        assertThat(challenge.getStatus()).isEqualTo(OtpStatus.LOCKED);
        assertThat(service.verify("challenge-1", "123456", NOW).accepted()).isFalse();
    }

    @Test
    void validOtpMovesToVerifiedAndConsumeMakesItSingleUse() {
        AuthOtpChallenge challenge = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .otpHash(new OtpHasher("test-pepper").hash("123456"))
                .status(OtpStatus.PENDING).expiresAt(NOW.plusSeconds(300))
                .failedAttempts(0).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdForUpdate("challenge-1")).thenReturn(Optional.of(challenge));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthOtpChallengeService service = service(repository);

        assertThat(service.verify("challenge-1", "123456", NOW).accepted()).isTrue();
        assertThat(challenge.getStatus()).isEqualTo(OtpStatus.VERIFIED);
        service.consume("challenge-1", NOW);
        assertThat(challenge.getStatus()).isEqualTo(OtpStatus.CONSUMED);
        assertThat(service.verify("challenge-1", "123456", NOW).accepted()).isFalse();
    }

    @Test
    void expiredOtpCannotBeVerified() {
        AuthOtpChallenge challenge = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.PASSWORD_RESET).subject("user@example.com")
                .otpHash(new OtpHasher("test-pepper").hash("123456"))
                .status(OtpStatus.PENDING).expiresAt(NOW.minusSeconds(1)).failedAttempts(0).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdForUpdate("challenge-1")).thenReturn(Optional.of(challenge));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service(repository).verify("challenge-1", "123456", NOW).accepted()).isFalse();
        assertThat(challenge.getStatus()).isEqualTo(OtpStatus.EXPIRED);
    }

    @Test
    void optimisticLockConflictIsTranslatedToSafeDomainException() {
        AuthOtpChallenge challenge = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .otpHash(new OtpHasher("test-pepper").hash("123456"))
                .status(OtpStatus.PENDING).expiresAt(NOW.plusSeconds(300)).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdForUpdate("challenge-1")).thenReturn(Optional.of(challenge));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenThrow(new ObjectOptimisticLockingFailureException(
                AuthOtpChallenge.class, "challenge-1"));

        assertThatThrownBy(() -> service(repository).verify("challenge-1", "123456", NOW))
                .isInstanceOf(AuthOtpChallengeService.ConflictException.class);
    }

    @Test
    void resendCannotHappenBeforeCooldownOrAfterFiveSends() {
        AuthOtpChallenge challenge = AuthOtpChallenge.builder().id("challenge-1")
                .purpose(OtpPurpose.REGISTRATION).subject("user@example.com")
                .otpHash("old-hash").status(OtpStatus.PENDING).expiresAt(NOW.plusSeconds(300))
                .resendAvailableAt(NOW.plusSeconds(60)).sendWindowStartedAt(NOW)
                .sendCount(1).build();
        AuthOtpChallengeRepository repository = mock(AuthOtpChallengeRepository.class);
        when(repository.findByIdForUpdate("challenge-1")).thenReturn(Optional.of(challenge));
        when(repository.saveAndFlush(any(AuthOtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthOtpChallengeService service = service(repository);

        assertThatThrownBy(() -> service.resend("challenge-1", NOW.plusSeconds(30)))
                .isInstanceOf(AuthOtpChallengeService.CooldownException.class);

        challenge.setResendAvailableAt(NOW);
        challenge.setSendCount(5);
        assertThatThrownBy(() -> service.resend("challenge-1", NOW.plusSeconds(60)))
                .isInstanceOf(AuthOtpChallengeService.RateLimitException.class);
    }

    private AuthOtpChallengeService service(AuthOtpChallengeRepository repository) {
        return new OtpChallengeServiceImpl(repository, new OtpGenerator(),
                new OtpHasher("test-pepper"), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
