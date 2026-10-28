package com.haloticket.api_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Bean("reactiveJwtDecoder")
    ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = org.springframework.util.StringUtils.hasText(jwkSetUri)
                ? NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000,http://localhost:5174,https://haloticket-web.pages.dev}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        java.util.List<String> origins = new java.util.ArrayList<>(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        if (!origins.contains("https://haloticket-web.pages.dev")) {
            origins.add("https://haloticket-web.pages.dev");
        }
        if (!origins.contains("https://*.pages.dev")) {
            origins.add("https://*.pages.dev");
        }

        corsConfig.setAllowedOriginPatterns(origins);
        corsConfig.setMaxAge(3600L);
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        corsConfig.setAllowedHeaders(Collections.singletonList("*"));
        // the web reads how long to wait from a 429
        corsConfig.setExposedHeaders(Arrays.asList("Retry-After"));
        corsConfig.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        return source;
    }

    @Value("${gateway.authz.enabled:true}")
    private boolean authzEnabled = true;

    /**
     * The AI routes that analyse event data through the language model (data analysis, voucher strategy) need ROLE_ADMIN.
     * Set it to false to let organizers in: ai-assistant then limits each of them to the events they manage (see
     * AI_ANALYSIS_ORGANIZER_ENABLED there). The organizer agent is not scoped yet and needs ROLE_ADMIN whatever this says.
     * Only has an effect while authzEnabled is true.
     */
    @Value("${gateway.authz.ai-analysis-admin-only.enabled:true}")
    private boolean aiAnalysisAdminOnly = true;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> {
                    exchange
                            // second layer behind InternalPathBlockFilter
                            .pathMatchers("/api/internal/**", "/*/api/internal/**", "/actuator/**", "/eureka/**").denyAll()
                            .pathMatchers(HttpMethod.OPTIONS).permitAll()
                            .pathMatchers("/ws/**", "/api/payments/vnpay/ipn", "/api/payments/momo/ipn", "/api/payments/zalopay/ipn", "/api/payments/sepay/webhook").permitAll()
                            .pathMatchers("/api/events/admin/reports", "/api/events/admin/reports/**",
                                    "/api/payments/admin/report-payments")
                            .access(SecurityConfig::adminOnly);
                    if (authzEnabled) {
                        // before the generic /api/organizer/** rule further down, which only asks for a login
                        exchange.pathMatchers("/api/organizer/agent-chat").access(SecurityConfig::adminOnly);
                        if (aiAnalysisAdminOnly) {
                            exchange.pathMatchers("/api/organizer/data-analysis", "/api/organizer/voucher-strategy")
                                    .access(SecurityConfig::adminOnly);
                        }
                        exchange.pathMatchers("/api/admin/**", "/api/payouts/admin/**", "/api/users/admin", "/api/users/admin/**")
                                .access(SecurityConfig::adminOnly);
                        // open to anonymous visitors: event, ticket-type and seat-map reads, the view and search counters,
                        // the homepage configuration. Everything else that writes needs a login.
                        exchange.pathMatchers(HttpMethod.POST, "/api/events/*/view", "/api/events/search-log").permitAll()
                                .pathMatchers(HttpMethod.GET, "/api/events/*/analytics", "/api/events/my-events", "/api/events/organizer-profile",
                                        "/api/events/*/members", "/api/events/*/members/**", "/api/events/*/transfer-settings").authenticated()
                                .pathMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                                .pathMatchers("/api/events/**").authenticated()
                                .pathMatchers("/api/tickets/checkin", "/api/tickets/checkin/**").authenticated()
                                .pathMatchers(HttpMethod.GET, "/api/tickets/**").permitAll()
                                .pathMatchers("/api/tickets/**").authenticated()
                                .pathMatchers(HttpMethod.GET, "/api/media/**").permitAll()
                                .pathMatchers("/api/media/**").authenticated()
                                .pathMatchers("/api/v1/**", "/ws-notifications/**").permitAll();
                    } else {
                        exchange.pathMatchers("/api/events/**", "/api/tickets/**", "/api/media/**",
                                "/api/v1/**", "/ws-notifications/**").permitAll();
                    }
                    exchange
                            .pathMatchers("/api/marketplace/listings/me", "/api/marketplace/sagas/**").authenticated()
                            .pathMatchers(HttpMethod.GET,
                                    "/api/marketplace/events", "/api/marketplace/events/**",
                                    "/api/marketplace/listings", "/api/marketplace/listings/*").permitAll()
                            .pathMatchers("/api/marketplace/**").authenticated()
                            .pathMatchers("/api/users/me").authenticated()
                            .pathMatchers("/api/users/**", "/api/bookings/**", "/api/payments/**", "/api/notifications/**")
                            .authenticated();
                    if (authzEnabled) {
                        exchange
                                .pathMatchers("/api/seats/**", "/api/gifts/**", "/api/payouts/**", "/api/organizer/**").authenticated()
                                // anonymous visitors use the chatbot widget and sign in, register and reset their password.
                                .pathMatchers("/api/customer/**", "/api/auth/**").permitAll()
                                .anyExchange().authenticated();
                    } else {
                        exchange.anyExchange().permitAll();
                    }
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder())));

        return http.build();
    }

    static Mono<AuthorizationDecision> adminOnly(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
                .map(auth -> new AuthorizationDecision(isReportAdmin(auth)))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    static boolean isReportAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) return false;
        Object direct = token.getToken().getClaim("roles");
        Object realm = token.getToken().getClaim("realm_access");
        return hasAdminRole(direct) || (realm instanceof java.util.Map<?, ?> map && hasAdminRole(map.get("roles")));
    }

    private static boolean hasAdminRole(Object roles) {
        return roles instanceof java.util.Collection<?> values && values.stream()
                .anyMatch(value -> "ADMIN".equalsIgnoreCase(String.valueOf(value))
                        || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(value)));
    }
}
