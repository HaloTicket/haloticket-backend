package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.service.impl.AuthApplicationServiceImpl;
import com.haloticket.user_service.service.AuthOtpChallengeService;
import com.haloticket.user_service.service.AuthApplicationService;
import com.haloticket.user_service.service.AuthRequests;
import com.haloticket.user_service.service.KeycloakAuthPort;
import com.haloticket.user_service.service.OtpDeliveryPort;
import com.haloticket.user_service.service.OtpRateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;

class AuthApplicationServiceImplTest {

    @Test
    void existingRegistrationIsRejectedBeforeOtpIsCreated() {
        AuthOtpChallengeService otpService = mock(AuthOtpChallengeService.class);
        OtpRateLimiter rateLimiter = mock(OtpRateLimiter.class);
        OtpDeliveryPort delivery = mock(OtpDeliveryPort.class);
        KeycloakAuthPort keycloak = mock(KeycloakAuthPort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        AuthApplicationServiceImpl service = new AuthApplicationServiceImpl(
                otpService, rateLimiter, delivery, keycloak, clock);
        doThrow(new AuthApplicationService.AuthException("USER_ALREADY_EXISTS", "User already registered", 409))
                .when(keycloak).ensureRegistrationAvailable("user@example.com");

        assertThatThrownBy(() -> service.startRegistration(
                new AuthRequests.RegisterStart("user@example.com", "Password1!", "req-1", "ip-hash")))
                .isInstanceOf(AuthApplicationService.AuthException.class)
                .hasMessage("User already registered");
        verify(otpService, never()).create(org.mockito.ArgumentMatchers.any());
        verify(delivery, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passwordResetByPhoneSendsOtpToResolvedAccountEmail() {
        AuthOtpChallengeService otpService = mock(AuthOtpChallengeService.class);
        OtpRateLimiter rateLimiter = mock(OtpRateLimiter.class);
        OtpDeliveryPort delivery = mock(OtpDeliveryPort.class);
        KeycloakAuthPort keycloak = mock(KeycloakAuthPort.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        AuthApplicationServiceImpl service = new AuthApplicationServiceImpl(
                otpService, rateLimiter, delivery, keycloak, clock);

        AuthOtpChallenge challenge = AuthOtpChallenge.builder()
                .id("challenge-1")
                .subject("user@example.com")
                .expiresAt(Instant.parse("2026-09-05T00:05:00Z"))
                .resendAvailableAt(Instant.parse("2026-09-05T00:01:00Z"))
                .build();
        when(keycloak.resolvePasswordResetEmail("0912345678")).thenReturn(Optional.of("user@example.com"));
        when(rateLimiter.tryAcquire("user@example.com", "ip-hash", clock.instant())).thenReturn(true);
        when(otpService.create(argThat(command -> "user@example.com".equals(command.subject()))))
                .thenReturn(new AuthOtpChallengeService.CreateResult(challenge, "123456"));

        service.startPasswordReset(new AuthRequests.PasswordForgot("0912345678", "req-1", "ip-hash"));

        verify(delivery).send("user@example.com", OtpPurpose.PASSWORD_RESET, "123456", challenge.getExpiresAt());
    }
}
