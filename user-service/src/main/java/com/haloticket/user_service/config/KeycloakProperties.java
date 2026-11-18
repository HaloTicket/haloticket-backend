package com.haloticket.user_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình kết nối Keycloak lấy từ tiền tố {@code keycloak}: địa chỉ máy chủ, realm và thông tin client.
 *
 * @param authServerUrl địa chỉ máy chủ Keycloak
 * @param realm tên realm
 * @param clientId mã client của user-service
 * @param clientSecret bí mật của client
 */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        String authServerUrl,
        String realm,
        String clientId,
        String clientSecret
) {
    /** Constructor gọn: giá trị thiếu hoặc trống được thay bằng mặc định cho môi trường phát triển. */
    public KeycloakProperties {
        if (authServerUrl == null || authServerUrl.isBlank()) {
            authServerUrl = "http://localhost:9080";
        }
        if (realm == null || realm.isBlank()) {
            realm = "haloticket-realm";
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = "user-service";
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            clientSecret = "user-service-secret";
        }
    }
}
