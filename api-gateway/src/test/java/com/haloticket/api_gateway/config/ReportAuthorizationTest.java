package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

class ReportAuthorizationTest {
    @Test
    void reportAndExportRequireAdminButPublicEventsStayPublic() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class, Endpoints.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            for (String path : List.of("/api/events/admin/reports", "/api/events/admin/reports/export", "/api/payments/admin/report-payments")) {
                client.get().uri(path).exchange().expectStatus().isUnauthorized();
                client.get().uri(path).headers(h -> h.setBearerAuth("customer")).exchange().expectStatus().isForbidden();
                client.get().uri(path).headers(h -> h.setBearerAuth("admin")).exchange().expectStatus().isOk();
            }
            client.get().uri("/api/events/public-event").exchange().expectStatus().isOk();
        }
    }
    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class TestConfiguration {
        @Bean
        SecurityWebFilterChain security(ServerHttpSecurity http) {
            var config = new SecurityConfig() {
                @Override ReactiveJwtDecoder jwtDecoder() {
                    return value -> Mono.just(Jwt.withTokenValue(value).header("alg", "none").subject("test")
                        .claim("realm_access", Map.of("roles", List.of("admin".equals(value) ? "ADMIN" : "CUSTOMER"))).build());
                }
            };
            ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
            return config.springSecurityFilterChain(http);
        }
    }
    @RestController
    static class Endpoints {
        @GetMapping({"/api/events/admin/reports", "/api/events/admin/reports/export", "/api/payments/admin/report-payments", "/api/events/public-event"})
        String report() { return "ok"; }
    }
}
