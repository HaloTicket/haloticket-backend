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

/** Cài đặt {@link KeycloakAuthPort} bằng Admin REST API của Keycloak, kèm bộ nhớ đệm token quản trị và kho reset token trong bộ nhớ. */
public class KeycloakAuthAdapter implements KeycloakAuthPort {

    private final KeycloakProperties properties;
    private final RestClient restClient;
    private final UserRepository userRepository;
    private final AuthOtpChallengeRepository challengeRepository;

    private volatile CachedToken cachedAdminToken;
    private final Map<String, ResetTokenEntry> resetTokens = new ConcurrentHashMap<>();

    /** Tạo adapter với cấu hình Keycloak, RestClient và các kho dữ liệu. */
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

    /** Từ chối nếu email đã có trong cơ sở dữ liệu nội bộ hoặc trên Keycloak (HTTP 409 {@code USER_ALREADY_EXISTS}). */
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

    /** Đăng ký tài khoản với mật khẩu người dùng chọn và email đã xác minh. */
    @Override
    public void register(String email, String password) {
        createUser(email, password, true);
    }

    /** Đăng ký tài khoản "ngủ" với mật khẩu ngẫu nhiên và email chưa xác minh, dùng cho tài khoản do quản trị viên hoặc lời mời tạo. */
    @Override
    public void registerDormant(String email) {
        createUser(email, randomPassword(), false);
    }

    /** Mật khẩu gồm 24 ký tự ngẫu nhiên cộng đủ mỗi loại ký tự mà chính sách mật khẩu của realm yêu cầu; không lưu, không ghi log, không trả về. */
    private static String randomPassword() {
        String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(28);
        for (int i = 0; i < 24; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.append("aZ7!").toString();
    }

    /**
     * Tạo người dùng trên Keycloak, gán vai trò mặc định rồi đồng bộ bản ghi nội bộ.
     *
     * @param email email, cũng dùng làm username
     * @param password mật khẩu ban đầu
     * @param emailVerified đánh dấu email đã xác minh hay chưa
     * @throws AuthException HTTP 409 khi đã tồn tại, 503 khi Keycloak không sẵn sàng
     */
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

    /** Tìm email tài khoản theo email hoặc số điện thoại trong cơ sở dữ liệu nội bộ; không thấy thì trả về rỗng. */
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

    /** Đưa số điện thoại về dạng bắt đầu bằng 0 (bỏ ký tự lạ và tiền tố 84 hoặc 840). */
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

    /** Tạo reset token ngẫu nhiên, hạn 10 phút, gắn với email của thử thách và giữ trong bộ nhớ (mất khi khởi động lại). */
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

    /** Đặt mật khẩu mới trên Keycloak bằng reset token còn hạn, rồi xóa token và đánh dấu email đã xác minh. */
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
     * Token đặt lại mật khẩu chỉ tồn tại sau khi mã gửi tới email này đã được xác minh, nên địa chỉ email coi như đã được chứng minh:
     * tài khoản tạo cho lời mời (chưa xác minh) sẽ thành đã xác minh ngay lần đầu chủ tài khoản đặt mật khẩu theo cách này.
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
            // mật khẩu đã được đặt lại; có thể đánh dấu email đã xác minh sau
        }
    }

    /** Đặt lại mật khẩu của người dùng Keycloak bằng quyền quản trị (không kiểm tra mật khẩu cũ ở tầng này). */
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

    /** Đăng nhập bằng grant {@code password}; sai thông tin thì báo HTTP 401 {@code INVALID_CREDENTIALS}. */
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

    /** Làm mới token; chỉ chấp nhận client của dịch vụ hoặc {@code haloticket-web}, hết phiên thì báo HTTP 401. */
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

    /** Lấy token quản trị bằng client credentials, dùng lại token trong bộ nhớ đệm cho tới trước lúc hết hạn 30 giây. */
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

    /** Tìm id người dùng Keycloak theo email chính xác; không có thì trả về {@code null}. */
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

    /** Gán vai trò realm cho người dùng; lỗi được bỏ qua để không làm gián đoạn đăng ký. */
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

    /** Tạo bản ghi người dùng nội bộ ({@code ACTIVE}, vai trò {@code CUSTOMER}) nếu chưa có theo id Keycloak hoặc email. */
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

    /** Phản hồi token của Keycloak. */
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("expires_in") int expiresIn
    ) {}

    /** Nội dung yêu cầu tạo người dùng gửi tới Keycloak. */
    public record KeycloakUserCreationRequest(
            String username,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            boolean emailVerified,
            List<KeycloakCredential> credentials
    ) {}

    /** Thông tin mật khẩu gửi tới Keycloak; {@code temporary} false nghĩa là không bắt đổi lại lần đăng nhập đầu. */
    public record KeycloakCredential(
            String type,
            String value,
            boolean temporary
    ) {}

    /** Người dùng Keycloak (chỉ các trường cần dùng). */
    public record KeycloakUserRepresentation(
            String id,
            String username,
            String email
    ) {}

    /** Vai trò realm của Keycloak (chỉ các trường cần dùng). */
    public record KeycloakRoleRepresentation(
            String id,
            String name
    ) {}

    /** Token quản trị được lưu đệm kèm thời điểm hết hạn. */
    private record CachedToken(String token, Instant expiresAt) {}

    /** Dữ liệu gắn với reset token: thử thách, email và thời điểm hết hạn. */
    private record ResetTokenEntry(String challengeId, String email, Instant expiresAt) {}
}
