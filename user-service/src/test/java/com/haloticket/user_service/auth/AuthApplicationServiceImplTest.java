package com.haloticket.user_service.auth;

import com.haloticket.user_service.auth.domain.AuthOtpChallenge;
import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.service.AuthApplicationServiceImpl;
import com.haloticket.user_service.auth.service.AuthOtpChallengeService;
import com.haloticket.user_service.auth.service.AuthRequests;
import com.haloticket.user_service.auth.service.KeycloakAuthPort;
import com.haloticket.user_service.auth.service.OtpDeliveryPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthApplicationServiceImplTest {

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
