package com.haloticket.user_service.auth.config;

import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.auth.service.AuthResponses;
import com.haloticket.user_service.auth.service.KeycloakAuthPort;
import com.haloticket.user_service.auth.service.OtpDeliveryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.time.Instant;

@Configuration
public class AuthProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(OtpDeliveryPort.class)
    OtpDeliveryPort otpDeliveryPort() {
        return (email, purpose, otp, expiresAt) -> { throw new AuthException("OTP_DELIVERY_UNAVAILABLE", "Verification email delivery is unavailable", 503); };
    }

    @Bean
    KeycloakAuthPort keycloakAuthPort() {
        return new KeycloakAuthPort() {
            public void register(String email, String password) { throw unavailable(); }
            public boolean matchesEmailAndPhone(String email, String phoneNumber) { throw unavailable(); }
            public String issueResetToken(String challengeId) { throw unavailable(); }
            public void resetPassword(String resetToken, String password) { throw unavailable(); }
            public AuthResponses.Login login(String email, String password) { throw unavailable(); }
            private AuthException unavailable() { return new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503); }
        };
    }
}
