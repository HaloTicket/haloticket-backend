package com.haloticket.ticket_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:9080/realms/haloticket-realm}") private String issuerUri;
    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") private String jwkSetUri;
    @Bean JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = jwkSetUri == null || jwkSetUri.isBlank() ? NimbusJwtDecoder.withIssuerLocation(issuerUri).build() : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/health").permitAll()
                .requestMatchers("/api/tickets/types/**", "/api/tickets/seat-layouts/**", "/api/tickets/seat-status").permitAll()
                .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder()))).build();
    }
}
