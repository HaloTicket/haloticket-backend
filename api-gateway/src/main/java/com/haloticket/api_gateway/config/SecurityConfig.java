package com.haloticket.api_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/eureka/**", "/actuator/**").permitAll()
                        .pathMatchers("/api/events/**", "/api/tickets/**", "/api/marketplace/**", "/api/v1/**").permitAll()
                        .pathMatchers("/api/users/me").authenticated()
                        .pathMatchers("/api/users/**", "/api/bookings/**", "/api/payments/**", "/api/notifications/**").authenticated()
                        .anyExchange().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtValidator(
                        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuerUri),
                                new AudienceValidator("api-gateway")))));

        return http.build();
    }
}
