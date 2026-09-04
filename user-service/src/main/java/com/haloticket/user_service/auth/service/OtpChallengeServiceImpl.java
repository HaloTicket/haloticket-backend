package com.haloticket.user_service.auth.service;

import com.haloticket.user_service.auth.OtpGenerator;
import com.haloticket.user_service.auth.OtpHasher;
import com.haloticket.user_service.auth.domain.AuthOtpChallenge;
import com.haloticket.user_service.auth.domain.OtpStatus;
import com.haloticket.user_service.auth.repository.AuthOtpChallengeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.OptimisticLockException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class OtpChallengeServiceImpl implements AuthOtpChallengeService {
    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    static final int MAX_SENDS = 5;
    static final int MAX_ATTEMPTS = 5;

    private final AuthOtpChallengeRepository repository;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final Clock clock;

    public OtpChallengeServiceImpl(AuthOtpChallengeRepository repository, OtpGenerator generator,
                                   OtpHasher hasher, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreateResult create(OtpChallengeCommand command) {
        return repository.findByIdempotencyKeyAndPurposeAndSubject(
                        command.idempotencyKey(), command.purpose(), command.subject())
                .map(challenge -> {
                    if (challenge.getStatus() != OtpStatus.PENDING) {
                        throw new AuthOtpChallengeService.IdempotencyKeyReuseException();
                    }
                    return new CreateResult(challenge, null);
                })
                .orElseGet(() -> createNew(command, clock.instant()));
    }

    private CreateResult createNew(OtpChallengeCommand command, Instant now) {
        String otp = generator.generate();
        AuthOtpChallenge challenge = AuthOtpChallenge.builder()
                .purpose(command.purpose()).subject(command.subject())
                .idempotencyKey(command.idempotencyKey()).requestIpHash(command.requestIpHash())
                .otpHash(hasher.hash(otp)).status(OtpStatus.PENDING)
                .expiresAt(now.plus(TTL)).resendAvailableAt(now.plus(RESEND_COOLDOWN))
                .sendWindowStartedAt(now).sendCount(1).failedAttempts(0).build();
        return new CreateResult(repository.save(challenge), otp);
    }

    @Override
    @Transactional
    public CreateResult resend(String challengeId, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        Instant now = requestedAt == null ? clock.instant() : requestedAt;
        if (challenge.getStatus() != OtpStatus.PENDING) {
            throw new AuthOtpChallengeService.InvalidStateException();
        }
        if (now.isBefore(challenge.getResendAvailableAt())) {
            throw new AuthOtpChallengeService.CooldownException();
        }
        if (!now.isBefore(challenge.getSendWindowStartedAt().plus(SEND_WINDOW))) {
            challenge.setSendWindowStartedAt(now);
            challenge.setSendCount(0);
        }
        if (challenge.getSendCount() >= MAX_SENDS) {
            throw new AuthOtpChallengeService.RateLimitException();
        }
        String otp = generator.generate();
        challenge.setOtpHash(hasher.hash(otp));
        challenge.setSendCount(challenge.getSendCount() + 1);
        challenge.setResendAvailableAt(now.plus(RESEND_COOLDOWN));
        challenge.setExpiresAt(now.plus(TTL));
        return new CreateResult(repository.save(challenge), otp);
    }

    @Override
    @Transactional
    public VerifyResult verify(String challengeId, String otp, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        Instant now = requestedAt == null ? clock.instant() : requestedAt;
        if (challenge.getStatus() != OtpStatus.PENDING) {
            return new VerifyResult(false);
        }
        if (!now.isBefore(challenge.getExpiresAt())) {
            challenge.setStatus(OtpStatus.EXPIRED);
            saveSafely(challenge);
            return new VerifyResult(false);
        }
        if (otp == null || !hasher.matches(otp, challenge.getOtpHash())) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
                challenge.setStatus(OtpStatus.LOCKED);
            }
            saveSafely(challenge);
            return new VerifyResult(false);
        }
        challenge.setStatus(OtpStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        saveSafely(challenge);
        return new VerifyResult(true);
    }

    @Override
    @Transactional
    public void consume(String challengeId, Instant requestedAt) {
        AuthOtpChallenge challenge = findForUpdate(challengeId);
        if (challenge.getStatus() != OtpStatus.VERIFIED) {
            throw new AuthOtpChallengeService.InvalidStateException();
        }
        challenge.setStatus(OtpStatus.CONSUMED);
        challenge.setConsumedAt(requestedAt == null ? clock.instant() : requestedAt);
        saveSafely(challenge);
    }

    private AuthOtpChallenge find(String challengeId) {
        return repository.findById(challengeId).orElseThrow(AuthOtpChallengeService.ChallengeNotFoundException::new);
    }

    private AuthOtpChallenge findForUpdate(String challengeId) {
        return repository.findByIdForUpdate(challengeId)
                .orElseThrow(AuthOtpChallengeService.ChallengeNotFoundException::new);
    }

    private void saveSafely(AuthOtpChallenge challenge) {
        try {
            repository.save(challenge);
        } catch (OptimisticLockException exception) {
            throw new AuthOtpChallengeService.ConflictException();
        }
    }
}
