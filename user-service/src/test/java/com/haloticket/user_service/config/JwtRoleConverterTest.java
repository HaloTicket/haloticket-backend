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
                .claim("roles", List.of("ROLE_USER", "ROLE_ADMIN", "ignored"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        Set<Role> roles = converter.toApplicationRoles(jwt);

        assertThat(roles).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ADMIN);
    }

    @Test
    void ignoresOrganizerAndStaffRolesFromTheToken() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("ROLE_USER", "ROLE_ORGANIZER", "ROLE_STAFF"))
                .build();

        assertThat(converter.toApplicationRoles(jwt)).containsExactly(Role.CUSTOMER);
        assertThat(converter.convert(jwt).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void aTokenWithOnlyOrganizerOrStaffStillCountsAsCustomer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-user-id")
                .claim("roles", List.of("ROLE_STAFF"))
                .build();

        assertThat(converter.toApplicationRoles(jwt)).containsExactly(Role.CUSTOMER);
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
