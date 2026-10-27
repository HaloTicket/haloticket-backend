package com.haloticket.user_service.service;

import com.haloticket.user_service.config.JwtRoleConverter;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Logging in copies CUSTOMER and ADMIN from the token into user_roles, but never ORGANIZER or STAFF. */
class UserRoleSyncTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserServiceImpl service = new UserServiceImpl(repository, new JwtRoleConverter(), mock(KeycloakAuthPort.class));

    private Set<Role> rolesStoredAfterLoginWith(List<String> tokenRoles, Set<Role> existing) {
        when(repository.findByKeycloakId("kc-1")).thenReturn(Optional.empty());
        when(repository.findByEmail("a@example.com")).thenReturn(Optional.empty());
        when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("kc-1")
                .claim("email", "a@example.com").claim("roles", tokenRoles).build();
        if (existing != null) {
            User user = User.builder().keycloakId("kc-1").email("a@example.com").status(UserStatus.ACTIVE).roles(new HashSet<>(existing)).build();
            when(repository.findByKeycloakId("kc-1")).thenReturn(Optional.of(user));
            when(repository.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        }
        service.syncCurrentUser(jwt);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());
        return saved.getValue().getRoles();
    }

    @Test
    void importsCustomerAndAdmin() {
        assertThat(rolesStoredAfterLoginWith(List.of("ROLE_USER", "ROLE_ADMIN"), null))
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.ADMIN);
    }

    @Test
    void doesNotImportOrganizerOrStaff() {
        assertThat(rolesStoredAfterLoginWith(List.of("ROLE_USER", "ROLE_ORGANIZER", "ROLE_STAFF"), null))
                .containsExactly(Role.CUSTOMER);
    }

    @Test
    void leavesRowsThatAlreadyHoldOrganizerOrStaffAlone() {
        assertThat(rolesStoredAfterLoginWith(List.of("ROLE_USER"), Set.of(Role.CUSTOMER, Role.ORGANIZER, Role.STAFF)))
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER, Role.STAFF);
    }
}
