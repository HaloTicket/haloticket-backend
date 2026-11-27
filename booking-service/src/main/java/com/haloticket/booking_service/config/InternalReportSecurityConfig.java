package com.haloticket.booking_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Cấu hình bảo mật của booking-service: route báo cáo và route admin cần vai trò ADMIN, các route còn lại đi qua chuỗi mặc định.
 */
@Configuration
@EnableMethodSecurity
public class InternalReportSecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${KEYCLOAK_ALLOWED_ISSUER_URIS:}")
    private String allowedIssuerUris;

    /**
     * Tạo bộ giải mã JWT: dùng {@code jwk-set-uri} nếu có, nếu không thì tra cứu theo issuer. Token phải đúng issuer.
     *
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

    /**
     * Chuỗi bảo mật thứ nhất ({@code @Order(1)}): chỉ áp dụng cho route báo cáo và {@code /api/bookings/admin/**}, bắt buộc vai trò ADMIN.
     *
     * @param http cấu hình bảo mật HTTP
     * @param roleConverter bộ chuyển JWT thành quyền
     * @return chuỗi bộ lọc bảo mật
     * @throws Exception khi dựng cấu hình thất bại
     */
    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(HttpSecurity http, BookingJwtRoleConverter roleConverter) throws Exception {
        return http
                .securityMatcher("/api/internal/bookings/report-facts", "/api/bookings/admin/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/internal/bookings/report-facts").hasRole("ADMIN")
                        .requestMatchers("/api/bookings/admin/**").hasRole("ADMIN")
                        .anyRequest().hasRole("ADMIN"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder()).jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    /**
     * Chuỗi bảo mật mặc định ({@code @Order(2)}): một số route cần đăng nhập, phần còn lại mở. Không đọc token ở route nội bộ.
     *
     * @param http cấu hình bảo mật HTTP
     * @param roleConverter bộ chuyển JWT thành quyền
     * @return chuỗi bộ lọc bảo mật
     * @throws Exception khi dựng cấu hình thất bại
     */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, BookingJwtRoleConverter roleConverter) throws Exception {
        BearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/bookings/by-code/**").authenticated()
                        .requestMatchers("/api/bookings/events/*/organizer/finance-facts").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/bookings/*/cancel").authenticated()
                        .anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Các route nội bộ còn lại trên chuỗi này (đọc giữa các service) là công khai và không dùng danh tính.
                        // Header Authorization cũ hoặc sai định dạng không được làm route đó trả 401, nên không đọc token.
                        .bearerTokenResolver(request -> isInternalRoute(request) ? null : defaultResolver.resolve(request))
                        .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    /**
     * @param request request đang xử lý
     * @return {@code true} nếu đường dẫn (bỏ context path) bắt đầu bằng {@code /api/internal/}
     */
    private static boolean isInternalRoute(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/internal/");
    }
}
