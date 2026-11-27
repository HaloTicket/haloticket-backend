package com.haloticket.payment_service.config;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cấu hình bảo mật của payment-service: resource server JWT, callback và IPN của cổng thanh toán mở công khai,
 * API chi trả theo vai trò.
 */
@Configuration
public class SecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${KEYCLOAK_ALLOWED_ISSUER_URIS:}")
    private String allowedIssuerUris;

    /**
     * Tạo bộ giải mã JWT: dùng {@code jwk-set-uri} nếu có, không thì dò từ issuer; luôn kiểm tra issuer.
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
     * Dựng chuỗi lọc bảo mật. Mở công khai: OPTIONS, actuator, callback và IPN của các cổng, tra cứu verify, tra cứu hàng loạt phương thức
     * (sau đó interceptor kiểm tra thêm). Báo cáo và API chi trả admin cần {@code ADMIN}; chính sách chi trả cần một trong các vai trò tài chính;
     * các API còn lại cần xác thực.
     *
     * @param http cấu hình HTTP security
     * @return chuỗi lọc đã dựng
     * @throws Exception khi cấu hình không hợp lệ
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/payments/admin/report-payments").hasRole("ADMIN")
                        .requestMatchers("/api/payouts/admin/policy/**", "/api/payouts/admin/policy")
                        .hasAnyRole("ADMIN", "FINANCE_ADMIN", "FINANCE_CONFIG", "SUPER_ADMIN")
                        .requestMatchers("/api/payouts/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/payouts/organizer/**").authenticated()
                        // chỉ các cuộc gọi do cổng thanh toán hoặc dịch vụ khác khởi tạo (IPN, webhook, tra cứu) được mở công khai;
                        // các đường tạo thanh toán (create-url, create-payload, POST /api/payments) bắt buộc đăng nhập
                        .requestMatchers(
                                "/api/payments/vnpay/ipn",
                                "/api/payments/sepay/webhook",
                                "/api/payments/momo/ipn",
                                "/api/payments/zalopay/ipn",
                                "/api/payments/shopeepay/ipn",
                                "/api/payments/verify/**",
                                "/api/payments/methods/bulk"
                        ).permitAll()
                        .requestMatchers("/api/payments/organizer-payout-account/**").authenticated()
                        .requestMatchers("/api/payments/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder())
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Chuyển claim {@code roles} và {@code realm_access.roles} của JWT thành authority dạng {@code ROLE_VIẾT_HOA}.
     *
     * @return bộ chuyển đổi xác thực JWT
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var roleNames = new HashSet<String>();
            Object directRoles = jwt.getClaims().get("roles");
            if (directRoles instanceof Collection<?> roles) {
                roles.stream().filter(String.class::isInstance).map(String.class::cast).forEach(roleNames::add);
            }
            Object realmAccess = jwt.getClaims().get("realm_access");
            if (realmAccess instanceof Map<?, ?> claims && claims.get("roles") instanceof Collection<?> roles) {
                roles.stream().filter(String.class::isInstance).map(String.class::cast).forEach(roleNames::add);
            }
            List<GrantedAuthority> authorities = roleNames.stream()
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase(Locale.ROOT))
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                    .toList();
            return authorities;
        });
        return converter;
    }
}
