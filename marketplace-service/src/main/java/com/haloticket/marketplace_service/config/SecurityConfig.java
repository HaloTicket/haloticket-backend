package com.haloticket.marketplace_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cấu hình bảo mật resource server cho {@code marketplace-service}: xác thực JWT do Keycloak cấp.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:9080/realms/haloticket-realm}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    /**
     * Tạo {@code JwtDecoder}: dùng {@code jwk-set-uri} nếu được cấu hình, ngược lại dò theo {@code issuer-uri};
     * luôn kiểm tra claim issuer khớp với {@code issuer-uri}.
     *
     * @return decoder giải mã và kiểm tra JWT
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = jwkSetUri == null || jwkSetUri.isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(issuerUri).build()
                : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    /**
     * Khai báo luật truy cập: tắt CSRF, cho phép OPTIONS và actuator công khai, các API xem danh sách tin bán
     * và sự kiện bằng GET được truy cập tự do, còn lại bắt buộc đăng nhập bằng JWT.
     *
     * @param http đối tượng cấu hình bảo mật HTTP
     * @return chuỗi filter bảo mật
     * @throws Exception khi xây dựng cấu hình thất bại
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/marketplace/listings/me", "/api/marketplace/sagas/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/marketplace/listings", "/api/marketplace/listings/*", "/api/marketplace/events", "/api/marketplace/events/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder())))
                .build();
    }
}
