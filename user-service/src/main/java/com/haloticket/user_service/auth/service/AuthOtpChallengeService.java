package com.haloticket.user_service.auth.service;

import com.haloticket.user_service.auth.domain.AuthOtpChallenge;

import java.time.Instant;

public interface AuthOtpChallengeService {
    CreateResult create(OtpChallengeCommand command);
    CreateResult resend(String challengeId, Instant now);
    VerifyResult verify(String challengeId, String otp, Instant now);
    void consume(String challengeId, Instant now);

    record CreateResult(AuthOtpChallenge challenge, String plaintextOtp) {}
    record VerifyResult(boolean accepted) {}

    class CooldownException extends RuntimeException {}
    class RateLimitException extends RuntimeException {}
    class ChallengeNotFoundException extends RuntimeException {}
    class InvalidStateException extends RuntimeException {}
    class ConflictException extends RuntimeException {}
    class IdempotencyKeyReuseException extends RuntimeException {}
}
