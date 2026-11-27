package com.haloticket.event_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Cấu hình bảo mật resource server của event-service: xác thực JWT và quy tắc truy cập cho từng nhóm đường dẫn. */
@Configuration
public class SecurityConfig {
    private final JwtRoleConverter jwtRoleConverter;

    /**
     * Nhận bộ chuyển đổi JWT dùng để lấy vai trò khi xác thực.
     * @param jwtRoleConverter bộ chuyển đổi JWT thành token xác thực có quyền
     */
    public SecurityConfig(JwtRoleConverter jwtRoleConverter) { this.jwtRoleConverter = jwtRoleConverter; }
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${KEYCLOAK_ALLOWED_ISSUER_URIS:}")
    private String allowedIssuerUris;

    /**
     * Tạo bộ giải mã JWT: dùng {@code jwk-set-uri} nếu có, nếu không thì dò theo {@code issuer-uri}; luôn kiểm tra issuer.
     * @return bộ giải mã JWT
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(issuerValidator());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> issuerValidator() {
        Set<String> allowedIssuers = new LinkedHashSet<>();
        if (StringUtils.hasText(issuerUri)) {
            allowedIssuers.add(issuerUri.trim());
        }
        if (StringUtils.hasText(allowedIssuerUris)) {
            Arrays.stream(allowedIssuerUris.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(allowedIssuers::add);
        }
        if (allowedIssuers.size() <= 1) {
            return JwtValidators.createDefaultWithIssuer(issuerUri);
        }
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS, iss -> iss != null && allowedIssuers.contains(iss.toString())));
    }

    private static final String[] ADMIN_WRITE_PATHS = {"/api/events/admin/config/**", "/api/events/categories", "/api/events/categories/**",
            "/api/events/venues", "/api/events/venues/**"};

    /**
     * Dựng chuỗi bộ lọc bảo mật: tắt CSRF (API dùng token), cho phép công khai các yêu cầu đọc sự kiện, lượt xem và voucher nội bộ,
     * yêu cầu vai trò ADMIN cho báo cáo, duyệt sự kiện và mọi thao tác ghi vào cấu hình, danh mục, địa điểm, còn lại bắt buộc đăng nhập.
     * Token JWT được giải mã bằng {@link #jwtDecoder()} và chuyển thành quyền bằng bộ chuyển đổi vai trò.
     * @param http đối tượng cấu hình bảo mật HTTP của Spring Security
     * @return chuỗi bộ lọc bảo mật đã dựng
     * @throws Exception khi cấu hình bảo mật không hợp lệ
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/internal/notification-teams/*",
                                "/api/internal/notification-teams/*/access",
                                "/api/internal/notification-teams/*/staff").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/events/organizer-profile").authenticated()
                        .requestMatchers("/api/events/my-events").authenticated()
                        .requestMatchers("/api/events/admin/reports/**").hasRole("ADMIN")
                        .requestMatchers("/api/events/approvals", "/api/events/*/approve", "/api/events/*/reject", "/api/events/*/revert-approval").hasRole("ADMIN")
                        // cấu hình trang chủ, danh mục và địa điểm: đọc công khai, mọi thao tác ghi dành cho quản trị viên nền tảng
                        // (EventScopeWebConfig kiểm tra lại lần nữa bên trong service)
                        .requestMatchers(HttpMethod.POST, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events/*/view").permitAll()
                        // booking-service ghi nhận lượt dùng voucher bằng token nội bộ dùng chung; controller sẽ kiểm tra token đó
                        .requestMatchers(HttpMethod.POST, "/api/events/vouchers/*/use").permitAll()
                        .requestMatchers("/api/events/test-questions").permitAll()
                        .requestMatchers("/api/events/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder()).jwtAuthenticationConverter(jwtRoleConverter)))
                .build();
    }
}
