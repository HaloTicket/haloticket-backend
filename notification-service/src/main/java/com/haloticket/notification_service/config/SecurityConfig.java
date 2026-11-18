package com.haloticket.notification_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cấu hình bảo mật của notification-service: dịch vụ là resource server xác thực JWT, không giữ session.
 * Mọi yêu cầu được cho qua ở tầng Spring Security; việc phân quyền theo từng endpoint do interceptor
 * {@code NotificationScopeGuard} đảm nhiệm.
 */
@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    /**
     * Tạo bộ giải mã JWT. Dùng {@code jwk-set-uri} nếu được cấu hình, ngược lại tự dò khóa từ issuer;
     * luôn kiểm tra claim issuer khớp {@code issuer-uri}.
     *
     * @return bộ giải mã JWT dùng cho resource server
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    /**
     * Dựng chuỗi lọc bảo mật: tắt CSRF, chế độ STATELESS, cho qua mọi request và xác thực JWT khi có.
     * Quyền (authority) lấy từ claim {@code roles} và {@code realm_access.roles}, chuẩn hóa thành
     * dạng {@code ROLE_VIẾT_HOA}.
     *
     * @param http đối tượng cấu hình HTTP security
     * @return chuỗi lọc đã dựng
     * @throws Exception khi cấu hình không hợp lệ
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(resource -> resource.decoder(jwtDecoder()).jwtAuthenticationConverter(jwt -> {
                    Set<String> roles = new HashSet<>();
                    List<String> direct = jwt.getClaimAsStringList("roles");
                    if (direct != null) roles.addAll(direct);
                    if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm && realm.get("roles") instanceof Collection<?> values) {
                        values.forEach(value -> roles.add(String.valueOf(value)));
                    }
                    return new JwtAuthenticationToken(jwt, roles.stream()
                            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase(Locale.ROOT))
                            .map(SimpleGrantedAuthority::new).toList(), jwt.getSubject());
                })))
                .build();
    }
}
