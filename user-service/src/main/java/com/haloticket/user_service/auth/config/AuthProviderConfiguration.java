package com.haloticket.user_service.auth.config;

import com.haloticket.user_service.auth.delivery.KeycloakAuthAdapter;
import com.haloticket.user_service.auth.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.auth.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.auth.service.AuthResponses;
import com.haloticket.user_service.auth.service.KeycloakAuthPort;
import com.haloticket.user_service.auth.service.OtpDeliveryPort;
import com.haloticket.user_service.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
public class AuthProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(OtpDeliveryPort.class)
    OtpDeliveryPort otpDeliveryPort() {
        return (email, purpose, otp, expiresAt) -> {
            throw new AuthException("OTP_DELIVERY_UNAVAILABLE", "Verification email delivery is unavailable", 503);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "keycloak.enabled", havingValue = "true", matchIfMissing = true)
    KeycloakAuthPort keycloakAuthAdapter(
            KeycloakProperties properties,
            UserRepository userRepository,
            AuthOtpChallengeRepository challengeRepository
    ) {
        RestClient restClient = RestClient.builder().build();
        return new KeycloakAuthAdapter(properties, restClient, userRepository, challengeRepository);
    }

    @Bean
    @ConditionalOnMissingBean(KeycloakAuthPort.class)
    KeycloakAuthPort fallbackKeycloakAuthPort() {
        return new KeycloakAuthPort() {
            public void register(String email, String password) { throw unavailable(); }
            public java.util.Optional<String> resolvePasswordResetEmail(String identifier) { throw unavailable(); }
            public String issueResetToken(String challengeId) { throw unavailable(); }
            public void resetPassword(String resetToken, String password) { throw unavailable(); }
            public AuthResponses.Login login(String email, String password) { throw unavailable(); }
            private AuthException unavailable() {
                return new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
            }
        };
    }
}
