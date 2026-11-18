package com.haloticket.user_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/** Cấu hình bảo mật của user-service: resource server JWT, kiểm tra audience và quy tắc phân quyền theo đường dẫn. */
@Configuration
@EnableWebSecurity
// lớp bảo vệ thứ hai sau quy tắc theo đường dẫn bên dưới: @PreAuthorize trên AdminUserController
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    /** Bộ giải mã JWT: lấy khóa từ jwk-set-uri nếu có, ngược lại từ issuer; kiểm tra issuer và audience {@code user-service}. */
    @Bean
    JwtDecoder jwtDecoder() {
        // các container lấy khóa qua địa chỉ nội bộ trong khi token vẫn mang issuer công khai.
        NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator("user-service")));
        return decoder;
    }

    /**
     * Chuỗi lọc bảo mật: tắt CSRF; cho phép OPTIONS, actuator, {@code /api/auth/**} và {@code /api/internal/**}; đường dẫn quản trị
     * cần vai trò ADMIN; còn lại phải đăng nhập.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/internal/**").permitAll() // Các lệnh gọi nội bộ từ những microservice khác
                        .requestMatchers("/api/users/me").authenticated()
                        .requestMatchers("/api/users/admin", "/api/users/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtRoleConverter)
                        .decoder(jwtDecoder())))
                .build();
    }
}
