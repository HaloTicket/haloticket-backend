package com.haloticket.event_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration
public class SecurityConfig {
    private final JwtRoleConverter jwtRoleConverter;

    public SecurityConfig(JwtRoleConverter jwtRoleConverter) { this.jwtRoleConverter = jwtRoleConverter; }
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

    private static final String[] ADMIN_WRITE_PATHS = {"/api/events/admin/config/**", "/api/events/categories", "/api/events/categories/**",
            "/api/events/venues", "/api/events/venues/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .requestMatchers("/api/events/organizer-profile").authenticated()
                        .requestMatchers("/api/events/my-events").authenticated()
                        .requestMatchers("/api/events/admin/reports/**").hasRole("ADMIN")
                        .requestMatchers("/api/events/approvals", "/api/events/*/approve", "/api/events/*/reject", "/api/events/*/revert-approval").hasRole("ADMIN")
                        // homepage configuration, categories and venues: reads are public, every write is for the platform admin
                        // (EventScopeWebConfig checks the same again inside the service)
                        .requestMatchers(HttpMethod.POST, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, ADMIN_WRITE_PATHS).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events/*/view").permitAll()
                        // booking-service counts a voucher use with the shared internal token; the controller checks it
                        .requestMatchers(HttpMethod.POST, "/api/events/vouchers/*/use").permitAll()
                        .requestMatchers("/api/events/test-questions").permitAll()
                        .requestMatchers("/api/events/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder()).jwtAuthenticationConverter(jwtRoleConverter)))
                .build();
    }
}
