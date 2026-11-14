package com.haloticket.booking_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
@EnableMethodSecurity
public class InternalReportSecurityConfig {
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
                        // The internal routes left on this chain (service-to-service reads) are public and use no identity.
                        // A stale or malformed Authorization header must not turn one of them into a 401, so the token is not read.
                        .bearerTokenResolver(request -> isInternalRoute(request) ? null : defaultResolver.resolve(request))
                        .jwt(jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(roleConverter)))
                .build();
    }

    private static boolean isInternalRoute(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/internal/");
    }
}
