package com.haloticket.user_service.service;

public final class AuthRequests {
    private AuthRequests() {}
    public record RegisterStart(String email, String password, String idempotencyKey, String requestIpHash) {}
    public record RegisterVerify(String challengeId, String email, String password, String otp) {}
    public record Challenge(String challengeId, String email) {}
    public record PasswordForgot(String identifier, String idempotencyKey, String requestIpHash) {}
    public record OtpVerify(String challengeId, String otp) {}
    public record PasswordReset(String resetToken, String password) {}
    public record Login(String email, String password) {}
    public record Refresh(String refreshToken, String clientId) {}
}
