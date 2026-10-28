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
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.user_service.service.KeycloakAuthPort;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceImplTest {
    private final UserRepository userRepository = mock(UserRepository.class);
    private final KeycloakAuthPort keycloakAuthPort = mock(KeycloakAuthPort.class);
    private final UserServiceImpl service = new UserServiceImpl(userRepository, new JwtRoleConverter(), keycloakAuthPort);

    @Test
    void googlePictureIsDefaultButDoesNotReplaceUploadedAvatar() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("google-user")
                .claim("email", "user@example.com").claim("picture", "https://lh3.googleusercontent.com/photo")
                .build();
        User existing = User.builder().id("user-id").keycloakId("google-user").email("user@example.com").build();
        when(userRepository.findByKeycloakId("google-user")).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.syncCurrentUser(jwt).getAvatarUrl()).isEqualTo("https://lh3.googleusercontent.com/photo");
        existing.setAvatarUrl("https://bucket.s3.ap-southeast-1.amazonaws.com/avatars/custom.png");
        assertThat(service.syncCurrentUser(jwt).getAvatarUrl()).endsWith("/avatars/custom.png");
    }

    @Test
    void invitingAnUnknownAddressCreatesAnAccountWithoutAPasswordAndReturnsNone() {
        User created = User.builder().id("new-id").email("fresh@example.com").fullName("fresh").build();
        when(userRepository.findByEmail("fresh@example.com")).thenReturn(Optional.empty(), Optional.of(created));
        com.haloticket.user_service.dto.request.UserInviteRequest request = new com.haloticket.user_service.dto.request.UserInviteRequest();
        request.setEmail("  Fresh@Example.com ");

        com.haloticket.user_service.dto.response.UserInviteResponse response = service.inviteUser(request);

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.getUserId()).isEqualTo("new-id");
        verify(keycloakAuthPort).registerDormant("fresh@example.com");
        // no flow may choose a password for somebody else
        verify(keycloakAuthPort, never()).register(any(), any());
        assertThat(java.util.Arrays.stream(com.haloticket.user_service.dto.response.UserInviteResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).noneMatch(name -> name.toLowerCase().contains("password"));
    }

    @Test
    void invitingAnExistingAccountDoesNotTouchKeycloak() {
        User existing = User.builder().id("old-id").email("known@example.com").fullName("Known").build();
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));
        com.haloticket.user_service.dto.request.UserInviteRequest request = new com.haloticket.user_service.dto.request.UserInviteRequest();
        request.setEmail("known@example.com");

        com.haloticket.user_service.dto.response.UserInviteResponse response = service.inviteUser(request);

        assertThat(response.isNewUser()).isFalse();
        verify(keycloakAuthPort, never()).registerDormant(any());
        verify(keycloakAuthPort, never()).register(any(), any());
    }

    @Test
    void invitingABadAddressIsRefused() {
        com.haloticket.user_service.dto.request.UserInviteRequest request = new com.haloticket.user_service.dto.request.UserInviteRequest();
        request.setEmail("not-an-address");
        assertThatThrownBy(() -> service.inviteUser(request)).isInstanceOf(ResponseStatusException.class);
        verify(keycloakAuthPort, never()).registerDormant(any());
    }


    @Test
    void missingGooglePictureLeavesAvatarEmptyForInitialFallback() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("google-user")
                .claim("email", "user@example.com").build();
        when(userRepository.findByKeycloakId("google-user")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.syncCurrentUser(jwt).getAvatarUrl()).isNull();
    }

    

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
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
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
                .keycloakId("identity-other")
                .email("other@example.com")
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(currentUser));
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(emailOwner));

        assertThatThrownBy(() -> service.syncCurrentUser(jwt))
                .isInstanceOf(ResponseStatusException.class)
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
                .keycloakId("identity-existing")
                .email("existing@example.com")
                .build();

        when(userRepository.findByKeycloakId("kc-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(emailOwner));

        assertThatThrownBy(() -> service.syncCurrentUser(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void syncCurrentUserPreservesExistingFullNameWhenJwtNameIsBlank() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("name", "   ")
                .claim("given_name", "")
                .claim("family_name", "")
                .claim("preferred_username", "")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        User existing = User.builder()
                .keycloakId("kc-123")
                .email("user@example.com")
                .fullName("Manually Updated Name")
                .status(UserStatus.ACTIVE)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.syncCurrentUser(jwt);

        assertThat(response.getFullName()).isEqualTo("Manually Updated Name");
        assertThat(existing.getFullName()).isEqualTo("Manually Updated Name");
    }

    @Test
    void syncCurrentUserDoesNotReplacePersistedProfileNameWithIdentityClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-123")
                .claim("email", "user@example.com")
                .claim("name", "Google Account Name")
                .claim("roles", List.of("ROLE_USER"))
                .build();
        User existing = User.builder()
                .keycloakId("kc-123")
                .email("user@example.com")
                .fullName("Name Saved From Profile")
                .status(UserStatus.ACTIVE)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.syncCurrentUser(jwt);

        assertThat(response.getFullName()).isEqualTo("Name Saved From Profile");
        assertThat(existing.getFullName()).isEqualTo("Name Saved From Profile");
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
                .status(UserStatus.ACTIVE)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.CUSTOMER))
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
        request.setRoles(Set.of(Role.CUSTOMER));
        when(userRepository.findByKeycloakId("kc-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = service.createUser(request);

        assertThat(response.getKeycloakId()).isEqualTo("kc-123");
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getUserByKeycloakIdFailsWhenMissing() {
        when(userRepository.findByKeycloakId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserByKeycloakId("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }
}
