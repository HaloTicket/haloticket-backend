package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.config.KeycloakProperties;
import com.haloticket.user_service.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.service.AuthResponses;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KeycloakAuthAdapterTest {

    @Test
    void refreshExchangesAndReturnsRotatedTokens() {
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=refresh_token"),
                        org.hamcrest.Matchers.containsString("refresh_token=old-refresh"))))
                .andRespond(withSuccess("""
                        {"access_token":"fresh-access","refresh_token":"fresh-refresh","id_token":"fresh-id","expires_in":1800}
                        """, MediaType.APPLICATION_JSON));

        AuthResponses.Login refreshed = adapter.refresh("old-refresh", "user-service");

        assertThat(refreshed.accessToken()).isEqualTo("fresh-access");
        assertThat(refreshed.refreshToken()).isEqualTo("fresh-refresh");
        assertThat(refreshed.expiresIn()).isEqualTo(1800);
        mockServer.verify();
    }

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private UserRepository userRepository;
    private AuthOtpChallengeRepository challengeRepository;
    private KeycloakAuthAdapter adapter;

    private final KeycloakProperties properties = new KeycloakProperties(
            "http://localhost:9080",
            "haloticket-realm",
            "user-service",
            "user-service-secret"
    );

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        userRepository = mock(UserRepository.class);
        challengeRepository = mock(AuthOtpChallengeRepository.class);

        adapter = new KeycloakAuthAdapter(
                properties,
                restClientBuilder.build(),
                userRepository,
                challengeRepository
        );
    }

    @Test
    void loginSuccessReturnsTokens() {
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "access_token": "mock-access-token",
                          "refresh_token": "mock-refresh-token",
                          "id_token": "mock-id-token",
                          "expires_in": 300
                        }
                        """, MediaType.APPLICATION_JSON));

        AuthResponses.Login login = adapter.login("user@example.com", "Secret123!");

        assertThat(login.accessToken()).isEqualTo("mock-access-token");
        assertThat(login.refreshToken()).isEqualTo("mock-refresh-token");
        assertThat(login.idToken()).isEqualTo("mock-id-token");
        assertThat(login.expiresIn()).isEqualTo(300);
        mockServer.verify();
    }

    @Test
    void loginFailureThrowsInvalidCredentials() {
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> adapter.login("user@example.com", "WrongPassword!"))
                .isInstanceOf(AuthException.class)
                .satisfies(error -> {
                    AuthException authException = (AuthException) error;
                    assertThat(authException.code()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(authException.status()).isEqualTo(401);
                });

        mockServer.verify();
    }

    @Test
    void registerSuccessCreatesKeycloakUserAndLocalUser() {
        // 1. Client credentials token request
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "access_token": "admin-token",
                          "expires_in": 300
                        }
                        """, MediaType.APPLICATION_JSON));

        // 2. User creation request
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andRespond(withCreatedEntity(URI.create("http://localhost:9080/admin/realms/haloticket-realm/users/keycloak-uuid-123")));

        // 3. Get ROLE_USER
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/roles/ROLE_USER"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andRespond(withSuccess("""
                        {
                          "id": "role-uuid-456",
                          "name": "ROLE_USER"
                        }
                        """, MediaType.APPLICATION_JSON));

        // 4. Assign role
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users/keycloak-uuid-123/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer admin-token"))
                .andRespond(withNoContent());

        when(userRepository.findByKeycloakId("keycloak-uuid-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newuser@example.com")).thenReturn(Optional.empty());

        adapter.register("newuser@example.com", "Password123!");

        verify(userRepository).save(any(User.class));
        mockServer.verify();
    }

    @Test
    void anInvitedAccountIsCreatedUnverifiedWithAPasswordNobodyKnows() {
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\": \"admin-token\", \"expires_in\": 300}", MediaType.APPLICATION_JSON));
        java.util.concurrent.atomic.AtomicReference<String> created = new java.util.concurrent.atomic.AtomicReference<>();
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> created.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withCreatedEntity(URI.create("http://localhost:9080/admin/realms/haloticket-realm/users/keycloak-uuid-9")));
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/roles/ROLE_USER"))
                .andRespond(withSuccess("{\"id\": \"role-uuid\", \"name\": \"ROLE_USER\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users/keycloak-uuid-9/role-mappings/realm"))
                .andRespond(withNoContent());
        when(userRepository.findByKeycloakId("keycloak-uuid-9")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("invited.person@example.com")).thenReturn(Optional.empty());

        adapter.registerDormant("invited.person@example.com");

        mockServer.verify();
        String body = created.get();
        assertThat(body).contains("\"emailVerified\":false");
        // the only credential is a long random value: nothing built from the address (not its local part, not a fixed suffix)
        java.util.regex.Matcher password = java.util.regex.Pattern.compile("\"value\":\"([^\"]+)\"").matcher(body);
        assertThat(password.find()).isTrue();
        assertThat(password.group(1)).hasSizeGreaterThanOrEqualTo(24).doesNotContain("invited").doesNotContain("@123");
    }

    @Test
    void twoInvitedAccountsDoNotGetTheSamePassword() {
        java.util.List<String> passwords = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            KeycloakAuthAdapter fresh = new KeycloakAuthAdapter(properties, builder.build(), userRepository, challengeRepository);
            server.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                    .andRespond(withSuccess("{\"access_token\": \"admin-token\", \"expires_in\": 300}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users"))
                    .andExpect(request -> passwords.add(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                    .andRespond(withCreatedEntity(URI.create("http://localhost:9080/admin/realms/haloticket-realm/users/k-" + passwords.size())));
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/roles/ROLE_USER"))).andRespond(withNoContent());
            fresh.registerDormant("same@example.com");
        }
        assertThat(passwords.get(0)).isNotEqualTo(passwords.get(1));
    }

    @Test
    void aVerifiedPasswordResetAlsoMarksTheEmailVerified() {
        when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(
                com.haloticket.user_service.entity.AuthOtpChallenge.builder().id("challenge-1").subject("invited@example.com").build()));
        String resetToken = adapter.issueResetToken("challenge-1");
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andRespond(withSuccess("{\"access_token\": \"admin-token\", \"expires_in\": 300}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users?email=invited@example.com&exact=true"))
                .andRespond(withSuccess("[{\"id\": \"kc-77\", \"username\": \"invited@example.com\", \"email\": \"invited@example.com\"}]", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users/kc-77/reset-password"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withNoContent());
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users/kc-77"))
                .andExpect(method(HttpMethod.PUT)).andExpect(content().json("{\"emailVerified\": true}")).andRespond(withNoContent());

        adapter.resetPassword(resetToken, "Brand-new-Pass1!");

        mockServer.verify();
    }

    @Test
    void anUnknownResetTokenChangesNothing() {
        assertThatThrownBy(() -> adapter.resetPassword("not-a-token", "Brand-new-Pass1!")).isInstanceOf(AuthException.class);
        mockServer.verify();
    }

    @Test
    void registerConflictThrowsUserAlreadyExists() {
        // Admin token
        mockServer.expect(requestTo("http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "access_token": "admin-token",
                          "expires_in": 300
                        }
                        """, MediaType.APPLICATION_JSON));

        // User creation 409 Conflict
        mockServer.expect(requestTo("http://localhost:9080/admin/realms/haloticket-realm/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.register("existing@example.com", "Password123!"))
                .isInstanceOf(AuthException.class)
                .satisfies(error -> {
                    AuthException authException = (AuthException) error;
                    assertThat(authException.code()).isEqualTo("USER_ALREADY_EXISTS");
                    assertThat(authException.status()).isEqualTo(409);
                });

        mockServer.verify();
    }

    @Test
    void resolvesPasswordResetEmailFromEmailOrNormalizedPhone() {
        User user = User.builder()
                .email("test@example.com")
                .phoneNumber("0912345678")
                .build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findFirstByPhoneNumber("0912345678")).thenReturn(Optional.of(user));

        assertThat(adapter.resolvePasswordResetEmail("TEST@example.com")).contains("test@example.com");
        assertThat(adapter.resolvePasswordResetEmail("+84 912345678")).contains("test@example.com");
        assertThat(adapter.resolvePasswordResetEmail("0987654321")).isEmpty();
    }
}
