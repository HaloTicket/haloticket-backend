package com.haloticket.user_service.auth;

import com.haloticket.user_service.auth.config.KeycloakProperties;
import com.haloticket.user_service.auth.delivery.KeycloakAuthAdapter;
import com.haloticket.user_service.auth.domain.AuthOtpChallenge;
import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.auth.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.auth.service.AuthResponses;
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
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KeycloakAuthAdapterTest {

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
                          "expires_in": 300
                        }
                        """, MediaType.APPLICATION_JSON));

        AuthResponses.Login login = adapter.login("user@example.com", "Secret123!");

        assertThat(login.accessToken()).isEqualTo("mock-access-token");
        assertThat(login.refreshToken()).isEqualTo("mock-refresh-token");
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
