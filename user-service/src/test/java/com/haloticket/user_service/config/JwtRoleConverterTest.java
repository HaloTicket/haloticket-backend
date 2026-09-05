package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void mapsKeycloakRolesToApplicationRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("ROLE_USER", "ROLE_ORGANIZER", "ignored"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        Set<Role> roles = converter.toApplicationRoles(jwt);

        assertThat(roles).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER);
    }

    @Test
    void defaultsToCustomerWhenTokenHasNoKnownRole() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("unknown"))
                .build();

        assertThat(converter.toApplicationRoles(jwt)).containsExactly(Role.CUSTOMER);
    }
}
