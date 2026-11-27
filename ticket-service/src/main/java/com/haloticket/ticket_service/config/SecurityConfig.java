package com.haloticket.ticket_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.http.HttpMethod;

/** Cấu hình bảo mật của ticket-service: xác thực JWT từ Keycloak và danh sách đường dẫn không cần đăng nhập. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:9080/realms/haloticket-realm}") private String issuerUri;
    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") private String jwkSetUri;
    @Value("${KEYCLOAK_ALLOWED_ISSUER_URIS:}") private String allowedIssuerUris;
    /** Tạo bộ giải mã JWT: dùng JWK set URI nếu có cấu hình, ngược lại tự tìm theo issuer; luôn kiểm tra issuer. */
    @Bean JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = jwkSetUri == null || jwkSetUri.isBlank() ? NimbusJwtDecoder.withIssuerLocation(issuerUri).build() : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
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
    /**
     * Dựng chuỗi lọc bảo mật: tắt CSRF, cho phép tự do actuator, các API nội bộ giữa dịch vụ (tự kiểm tra token trong controller)
     * và API công khai về loại vé, sơ đồ ghế; mọi yêu cầu còn lại phải đăng nhập bằng JWT.
     */
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/health").permitAll()
                // Endpoint này tự kiểm tra thông tin xác thực dịch vụ trước khi truy cập DB.
                .requestMatchers(HttpMethod.GET, "/api/internal/tickets/*/transfer-snapshot").permitAll()
                // Token dịch vụ được kiểm tra ngay trong controller trước khi truy cập DB.
                .requestMatchers(HttpMethod.POST, "/api/internal/tickets/stock-reservations", "/api/internal/tickets/stock-reservations/*/release").permitAll()
                // Tương tự cho việc mở lại giữ chỗ (thanh toán muộn) và các lần đọc đối soát (trang admin qua booking-service).
                .requestMatchers(HttpMethod.POST, "/api/internal/tickets/stock-reservations/*/reopen", "/api/internal/tickets/issued-counts").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/internal/tickets/stock-reconciliation").permitAll()
                .requestMatchers("/api/tickets/types/**", "/api/tickets/seat-layouts/**", "/api/tickets/seat-status").permitAll()
                .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder()))).build();
    }
}
