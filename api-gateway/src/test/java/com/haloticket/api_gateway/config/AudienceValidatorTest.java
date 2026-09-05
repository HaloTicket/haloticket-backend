package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("api-gateway");

    @Test
    void acceptsTokenForGatewayAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("aud", List.of("api-gateway"))
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutGatewayAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .claim("aud", List.of("haloticket-web"))
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }
}
