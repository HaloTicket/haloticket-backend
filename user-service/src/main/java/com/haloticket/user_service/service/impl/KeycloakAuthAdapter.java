package com.haloticket.user_service.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haloticket.user_service.config.KeycloakProperties;
import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.repository.AuthOtpChallengeRepository;
import com.haloticket.user_service.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.service.AuthResponses;
import com.haloticket.user_service.service.KeycloakAuthPort;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KeycloakAuthAdapter implements KeycloakAuthPort {

    private final KeycloakProperties properties;
    private final RestClient restClient;
    private final UserRepository userRepository;
    private final AuthOtpChallengeRepository challengeRepository;

    private volatile CachedToken cachedAdminToken;
    private final Map<String, ResetTokenEntry> resetTokens = new ConcurrentHashMap<>();

    public KeycloakAuthAdapter(
            KeycloakProperties properties,
            RestClient restClient,
            UserRepository userRepository,
            AuthOtpChallengeRepository challengeRepository
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
    }

    @Override
    public void ensureRegistrationAvailable(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new AuthException("USER_ALREADY_EXISTS", "User already registered", 409);
        }

        String existingUserId = lookupUserIdByEmail(obtainAdminToken(), normalizedEmail);
        if (existingUserId != null) {
            throw new AuthException("USER_ALREADY_EXISTS", "User already registered", 409);
        }
    }

    @Override
    public void register(String email, String password) {
        createUser(email, password, true);
    }

    @Override
    public void registerDormant(String email) {
        createUser(email, randomPassword(), false);
    }

    /** 24 random characters plus one of each class the realm password policy asks for; never stored, logged or returned. */
    private static String randomPassword() {
        String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(28);
        for (int i = 0; i < 24; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.append("aZ7!").toString();
    }

    private void createUser(String email, String password, boolean emailVerified) {
        String adminToken = obtainAdminToken();
        String createUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm() + "/users";

        String namePrefix = email;
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            namePrefix = email.substring(0, atIndex);
        }

        KeycloakUserCreationRequest requestBody = new KeycloakUserCreationRequest(
                email,
                email,
                namePrefix,
                "HaloTicket",
                true,
                emailVerified,
                List.of(new KeycloakCredential("password", password, false))
        );

        String keycloakUserId;
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(createUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            URI location = response.getHeaders().getLocation();
            if (location != null) {
                String path = location.getPath();
                keycloakUserId = path.substring(path.lastIndexOf('/') + 1);
            } else {
                keycloakUserId = lookupUserIdByEmail(adminToken, email);
            }
        } catch (HttpClientErrorException.Conflict conflict) {
            throw new AuthException("USER_ALREADY_EXISTS", "User already registered", 409);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 409) {
                throw new AuthException("USER_ALREADY_EXISTS", "User already registered", 409);
            }
            throw new AuthException("KEYCLOAK_ERROR", "Keycloak registration failed: " + ex.getMessage(), ex.getStatusCode().value());
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }

        // Gán Role ROLE_USER trong Keycloak
        assignDefaultRole(adminToken, keycloakUserId, "ROLE_USER");

        // Đồng bộ bản ghi users trong PostgreSQL nội bộ
        syncLocalUser(keycloakUserId, email);
    }

    @Override
    public Optional<String> resolvePasswordResetEmail(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String value = identifier.trim();
        if (value.contains("@")) {
            return userRepository.findByEmail(value.toLowerCase()).map(User::getEmail);
        }
        String phoneNumber = normalizePhone(value);
        if (phoneNumber.isBlank()) return Optional.empty();
        return userRepository.findFirstByPhoneNumber(phoneNumber).map(User::getEmail);
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("840") && digits.length() > 10) {
            digits = digits.substring(2); // "840912..." -> "0912..."
        } else if (digits.startsWith("84") && digits.length() > 9) {
            digits = "0" + digits.substring(2); // "84912..." -> "0912..."
        }
        return digits;
    }

    @Override
    public String issueResetToken(String challengeId) {
        AuthOtpChallenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new AuthException("CHALLENGE_NOT_FOUND", "Challenge not found", 404));

        String email = challenge.getSubject();
        String resetToken = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(600); // 10 phút

        resetTokens.put(resetToken, new ResetTokenEntry(challengeId, email, expiresAt));
        return resetToken;
    }

    @Override
    public void resetPassword(String resetToken, String password) {
        ResetTokenEntry entry = resetTokens.get(resetToken);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            resetTokens.remove(resetToken);
            throw new AuthException("RESET_TOKEN_INVALID", "Reset token is invalid or expired", 400);
        }

        String adminToken = obtainAdminToken();
        String keycloakUserId = lookupUserIdByEmail(adminToken, entry.email());
        if (keycloakUserId == null) {
            throw new AuthException("USER_NOT_FOUND", "User not found", 404);
        }

        String resetPasswordUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm()
                + "/users/" + keycloakUserId + "/reset-password";

        KeycloakCredential credential = new KeycloakCredential("password", password, false);

        try {
            restClient.put()
                    .uri(resetPasswordUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();

            resetTokens.remove(resetToken);
            markEmailVerified(adminToken, keycloakUserId);
        } catch (HttpClientErrorException ex) {
            throw new AuthException("KEYCLOAK_ERROR", "Password reset failed: " + ex.getMessage(), ex.getStatusCode().value());
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }
    }

    /**
     * A reset token only exists after the code mailed to this address was verified, so the address is proven: an account
     * created for an invitation (unverified) becomes verified the first time its owner sets a password this way.
     */
    private void markEmailVerified(String adminToken, String keycloakUserId) {
        try {
            restClient.put()
                    .uri(properties.authServerUrl() + "/admin/realms/" + properties.realm() + "/users/" + keycloakUserId)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("emailVerified", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {
            // the password is already reset; the flag can be set later
        }
    }

    @Override
    public void changePassword(String keycloakUserId, String newPassword) {
        String adminToken = obtainAdminToken();
        String resetPasswordUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm()
                + "/users/" + keycloakUserId + "/reset-password";
        KeycloakCredential credential = new KeycloakCredential("password", newPassword, false);
        try {
            restClient.put()
                    .uri(resetPasswordUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            throw new AuthException("KEYCLOAK_ERROR", "Password change failed: " + ex.getMessage(), ex.getStatusCode().value());
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }
    }

    @Override
    public AuthResponses.Login login(String email, String password) {
        String tokenUrl = properties.authServerUrl() + "/realms/" + properties.realm() + "/protocol/openid-connect/token";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("client_id", properties.clientId());
        formData.add("client_secret", properties.clientSecret());
        formData.add("username", email);
        formData.add("password", password);

        try {
            TokenResponse tokenResponse = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(TokenResponse.class);

            if (tokenResponse == null || tokenResponse.accessToken() == null) {
                throw new AuthException("INVALID_CREDENTIALS", "Invalid email or password", 401);
            }

            return new AuthResponses.Login(
                    tokenResponse.accessToken(),
                    tokenResponse.refreshToken(),
                    tokenResponse.idToken(),
                    tokenResponse.expiresIn()
            );
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401) {
                throw new AuthException("INVALID_CREDENTIALS", "Invalid email or password", 401);
            }
            throw new AuthException("KEYCLOAK_ERROR", "Login failed: " + ex.getMessage(), ex.getStatusCode().value());
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }
    }

    @Override
    public AuthResponses.Login refresh(String refreshToken, String clientId) {
        String requestedClient = clientId == null || clientId.isBlank() ? properties.clientId() : clientId;
        if (!requestedClient.equals(properties.clientId()) && !requestedClient.equals("haloticket-web")) {
            throw new AuthException("INVALID_CLIENT", "Token client is not supported", 400);
        }
        String tokenUrl = properties.authServerUrl() + "/realms/" + properties.realm() + "/protocol/openid-connect/token";
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("client_id", requestedClient);
        formData.add("refresh_token", refreshToken);
        if (requestedClient.equals(properties.clientId())) formData.add("client_secret", properties.clientSecret());
        try {
            TokenResponse response = restClient.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(formData)
                    .retrieve().body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new AuthException("INVALID_REFRESH_TOKEN", "Session has expired", 401);
            }
            return new AuthResponses.Login(response.accessToken(), response.refreshToken(), response.idToken(), response.expiresIn());
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401) {
                throw new AuthException("INVALID_REFRESH_TOKEN", "Session has expired", 401);
            }
            throw new AuthException("KEYCLOAK_ERROR", "Token refresh failed", ex.getStatusCode().value());
        } catch (ResourceAccessException | HttpServerErrorException ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }
    }

    private synchronized String obtainAdminToken() {
        Instant now = Instant.now();
        if (cachedAdminToken != null && cachedAdminToken.expiresAt().isAfter(now.plusSeconds(30))) {
            return cachedAdminToken.token();
        }

        String tokenUrl = properties.authServerUrl() + "/realms/" + properties.realm() + "/protocol/openid-connect/token";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", properties.clientId());
        formData.add("client_secret", properties.clientSecret());

        try {
            TokenResponse response = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new AuthException("KEYCLOAK_UNAVAILABLE", "Failed to obtain Keycloak admin token", 503);
            }

            int expiresIn = response.expiresIn() > 0 ? response.expiresIn() : 300;
            cachedAdminToken = new CachedToken(response.accessToken(), now.plusSeconds(expiresIn));
            return cachedAdminToken.token();
        } catch (Exception ex) {
            throw new AuthException("KEYCLOAK_UNAVAILABLE", "Identity provider is unavailable", 503);
        }
    }

    private String lookupUserIdByEmail(String adminToken, String email) {
        String searchUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm()
                + "/users?email=" + email + "&exact=true";

        List<KeycloakUserRepresentation> users = restClient.get()
                .uri(searchUrl)
                .header("Authorization", "Bearer " + adminToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<KeycloakUserRepresentation>>() {});

        if (users != null && !users.isEmpty()) {
            return users.get(0).id();
        }
        return null;
    }

    private void assignDefaultRole(String adminToken, String keycloakUserId, String roleName) {
        try {
            String roleUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm()
                    + "/roles/" + roleName;

            KeycloakRoleRepresentation role = restClient.get()
                    .uri(roleUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(KeycloakRoleRepresentation.class);

            if (role != null) {
                String mappingUrl = properties.authServerUrl() + "/admin/realms/" + properties.realm()
                        + "/users/" + keycloakUserId + "/role-mappings/realm";

                restClient.post()
                        .uri(mappingUrl)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(List.of(role))
                        .retrieve()
                        .toBodilessEntity();
            }
        } catch (Exception ignored) {
            // Không làm gián đoạn đăng ký nếu gán role thất bại
        }
    }

    private void syncLocalUser(String keycloakId, String email) {
        if (userRepository.findByKeycloakId(keycloakId).isPresent()
                || userRepository.findByEmail(email).isPresent()) {
            return;
        }

        String fullName = email;
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            fullName = email.substring(0, atIndex);
        }

        User user = User.builder()
                .keycloakId(keycloakId)
                .email(email)
                .fullName(fullName)
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.CUSTOMER))
                .build();

        userRepository.save(user);
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("expires_in") int expiresIn
    ) {}

    public record KeycloakUserCreationRequest(
            String username,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            boolean emailVerified,
            List<KeycloakCredential> credentials
    ) {}

    public record KeycloakCredential(
            String type,
            String value,
            boolean temporary
    ) {}

    public record KeycloakUserRepresentation(
            String id,
            String username,
            String email
    ) {}

    public record KeycloakRoleRepresentation(
            String id,
            String name
    ) {}

    private record CachedToken(String token, Instant expiresAt) {}

    private record ResetTokenEntry(String challengeId, String email, Instant expiresAt) {}
}
