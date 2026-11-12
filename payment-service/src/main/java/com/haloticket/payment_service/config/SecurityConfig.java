package com.haloticket.payment_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

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
                        .requestMatchers(
                                "/api/payments/vnpay/**",
                                "/api/payments/vietqr/**",
                                "/api/payments/sepay/**",
                                "/api/payments/momo/**",
                                "/api/payments/zalopay/**",
                                "/api/payments/shopeepay/**",
                                "/api/payments/verify/**",
                                "/api/payments/methods/bulk",
                                "/api/payments"
                        ).permitAll()
                        .requestMatchers("/api/payments/organizer-payout-account/**").authenticated()
                        .requestMatchers("/api/payments/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder())
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

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
