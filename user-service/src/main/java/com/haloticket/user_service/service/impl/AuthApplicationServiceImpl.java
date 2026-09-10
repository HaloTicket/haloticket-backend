package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.service.AuthApplicationService;
import com.haloticket.user_service.service.AuthOtpChallengeService;
import com.haloticket.user_service.service.AuthRequests;
import com.haloticket.user_service.service.AuthResponses;
import com.haloticket.user_service.service.KeycloakAuthPort;
import com.haloticket.user_service.service.OtpDeliveryPort;
import com.haloticket.user_service.service.OtpChallengeCommand;
import com.haloticket.user_service.service.OtpRateLimiter;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class AuthApplicationServiceImpl implements AuthApplicationService {
    private final AuthOtpChallengeService otpService;
    private final OtpRateLimiter rateLimiter;
    private final OtpDeliveryPort delivery;
    private final KeycloakAuthPort keycloak;
    private final Clock clock;

    public AuthApplicationServiceImpl(AuthOtpChallengeService otpService,
                                      OtpRateLimiter rateLimiter,
                                      OtpDeliveryPort delivery,
                                      KeycloakAuthPort keycloak,
                                      Clock clock) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.delivery = delivery;
        this.keycloak = keycloak;
        this.clock = clock;
    }

    @Override
    public AuthOtpChallengeService.CreateResult startRegistration(AuthRequests.RegisterStart request) {
        requirePassword(request.password());
        requireEmail(request.email());
        keycloak.ensureRegistrationAvailable(request.email());
        acquire(request.email(), request.requestIpHash());
        var result = otpService.create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, request.email(), request.idempotencyKey(), request.requestIpHash()));
        deliver(result, request.email(), OtpPurpose.REGISTRATION);
        return result;
    }

    @Override
    public void verifyRegistration(AuthRequests.RegisterVerify request) {
        requirePassword(request.password());
        var result = otpService.verify(request.challengeId(), request.otp(), clock.instant());
        if (!result.accepted()) {
            throw new AuthException("OTP_INVALID", "OTP is invalid or expired", 410);
        }
        keycloak.register(request.email(), request.password());
        otpService.consume(request.challengeId(), clock.instant());
    }

    @Override
    public AuthOtpChallengeService.CreateResult resendRegistration(AuthRequests.Challenge request) {
        var result = otpService.resend(request.challengeId(), clock.instant());
        deliver(result, result.challenge().getSubject(), OtpPurpose.REGISTRATION);
        return result;
    }

    @Override
    public AuthOtpChallengeService.CreateResult startPasswordReset(AuthRequests.PasswordForgot request) {
        if (request.identifier() == null || request.identifier().isBlank())
            throw new AuthException("INVALID_REQUEST", "Email or phone number is required", 400);
        String email = keycloak.resolvePasswordResetEmail(request.identifier())
                .orElseThrow(() -> new AuthException("RESET_NOT_AVAILABLE", "Unable to start password recovery", 400));
        acquire(email, request.requestIpHash());
        var result = otpService.create(new OtpChallengeCommand(
                OtpPurpose.PASSWORD_RESET, email, request.idempotencyKey(), request.requestIpHash()));
        deliver(result, email, OtpPurpose.PASSWORD_RESET);
        return result;
    }

    @Override
    public String verifyPasswordReset(AuthRequests.OtpVerify request) {
        var result = otpService.verify(request.challengeId(), request.otp(), clock.instant());
        if (!result.accepted()) {
            throw new AuthException("OTP_INVALID", "OTP is invalid or expired", 410);
        }
        return keycloak.issueResetToken(request.challengeId());
    }

    @Override
    public AuthOtpChallengeService.CreateResult resendPasswordReset(AuthRequests.Challenge request) {
        var result = otpService.resend(request.challengeId(), clock.instant());
        deliver(result, result.challenge().getSubject(), OtpPurpose.PASSWORD_RESET);
        return result;
    }

    @Override
    public void resetPassword(AuthRequests.PasswordReset request) {
        requirePassword(request.password());
        keycloak.resetPassword(request.resetToken(), request.password());
    }

    @Override
    public AuthResponses.Login login(AuthRequests.Login request) {
        return keycloak.login(request.email(), request.password());
    }

    @Override
    public AuthResponses.Login refresh(AuthRequests.Refresh request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new AuthException("INVALID_REFRESH_TOKEN", "Refresh token is required", 400);
        }
        return keycloak.refresh(request.refreshToken(), request.clientId());
    }

    private void deliver(AuthOtpChallengeService.CreateResult result, String email, OtpPurpose purpose) {
        if (result.plaintextOtp() != null) {
            delivery.send(email, purpose, result.plaintextOtp(), result.challenge().getExpiresAt());
        }
    }

    private void acquire(String subject, String ipHash) {
        if (!rateLimiter.tryAcquire(subject, ipHash, clock.instant())) {
            throw new AuthException("OTP_RATE_LIMITED", "Too many OTP requests", 429);
        }
    }

    private static void requireEmail(String email) {
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new AuthException("EMAIL_INVALID", "Email is invalid", 400);
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 32
                || !password.matches(".*[a-z].*")
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*\\d.*")
                || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new AuthException("PASSWORD_INVALID", "Password does not meet policy", 400);
        }
    }

    private static class AuthException extends AuthApplicationService.AuthException {
        private AuthException(String code, String message, int status) {
            super(code, message, status);
        }
    }
}
