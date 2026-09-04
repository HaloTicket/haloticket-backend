package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.config.JwtRoleConverter;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserServiceImpl service = new UserServiceImpl(userRepository, new JwtRoleConverter());

    @Test
    void syncCurrentUserCreatesUserFromJwtWhenMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("name", "Customer HaloTicket")
                .claim("roles", List.of("ROLE_USER"))
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.syncCurrentUser(jwt);

        assertThat(response.getKeycloakId()).isEqualTo("kc-123");
        assertThat(response.getEmail()).isEqualTo("user@example.com");
        assertThat(response.getFullName()).isEqualTo("Customer HaloTicket");
        assertThat(response.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.getIsActive()).isTrue();
    }

    @Test
    void syncCurrentUserRejectsEmailCollisionWhenKeycloakUserAlreadyExists() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "other@example.com")
                .build();
        User currentUser = User.builder()
                .keycloakId("kc-123")
                .email("user@example.com")
                .build();
        User emailOwner = User.builder()
                .keycloakId("kc-other")
                .email("other@example.com")
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(currentUser));
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(emailOwner));

        assertThatThrownBy(() -> service.syncCurrentUser(jwt))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void syncCurrentUserRejectsEmailOwnedByDifferentKeycloakUser() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-new")
                .claim("email", "existing@example.com")
                .build();
        User emailOwner = User.builder()
                .keycloakId("kc-existing")
                .email("existing@example.com")
                .build();

        when(userRepository.findByKeycloakId("kc-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(emailOwner));

        assertThatThrownBy(() -> service.syncCurrentUser(jwt))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateCurrentUserOnlyUpdatesEditableProfileFields() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        User existing = User.builder()
                .keycloakId("kc-123")
                .email("user@example.com")
                .fullName("Old Name")
                .isActive(true)
                .status(UserStatus.ACTIVE)
                .roles(java.util.Set.of(Role.CUSTOMER))
                .build();
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setFullName("New Name");
        request.setPhoneNumber("0900000000");
        request.setGender(Gender.MALE);
        request.setAvatarUrl("https://example.com/a.png");

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.updateCurrentUser(jwt, request);

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getPhoneNumber()).isEqualTo("0900000000");
        assertThat(response.getGender()).isEqualTo(Gender.MALE);
        assertThat(response.getAvatarUrl()).isEqualTo("https://example.com/a.png");
        verify(userRepository).save(existing);
    }

    @Test
    void createUserPersistsActiveUser() {
        UserCreateRequest request = new UserCreateRequest();
        request.setKeycloakId("kc-123");
        request.setEmail("user@example.com");
        request.setFullName("Customer HaloTicket");
        request.setRoles(java.util.Set.of(Role.CUSTOMER));
        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.createUser(request);

        assertThat(response.getKeycloakId()).isEqualTo("kc-123");
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.getIsActive()).isTrue();
    }

    @Test
    void getUserByKeycloakIdFailsWhenMissing() {
        when(userRepository.findByKeycloakId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserByKeycloakId("missing"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }
}
