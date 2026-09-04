package com.haloticket.user_service.auth.service;

public final class AuthResponses {
    private AuthResponses() {}
    public record Login(String accessToken, String refreshToken, long expiresIn) {}
    public record Challenge(String challengeId, java.time.Instant expiresAt, java.time.Instant resendAvailableAt) {}
    public record ResetToken(String resetToken, java.time.Instant expiresAt) {}
    public record Error(String code, String message, Integer retryAfterSeconds) {}
}
