package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway must not hand out service-to-service endpoints: not by their real path, not through the service-name
 * path the discovery locator used to create, and not by tricks with case, slashes, dots or percent-encoding.
 */
class GatewayExposureTest {

    private static final List<String> INTERNAL_PATHS = List.of(
            "/api/internal/users/x",
            "/api/internal/users/00000000-0000-0000-0000-000000000000/roles/ADMIN",
            "/api/internal/bookings/events/e1/stats",
            "/api/internal/bookings/report-facts",
            "/api/internal/tickets/t1/transfer-snapshot",
            "/api/internal/tickets/stock-reservations",
            "/api/internal/tickets/stock-reservations/b1/release",
            "/api/internal",
            "/user-service/api/internal/users/x",
            "/USER-SERVICE/api/internal/users/x",
            "/booking-service/api/internal/bookings/events/e1/stats",
            "/ticket-service/api/internal/tickets/stock-reservations",
            // case
            "/API/INTERNAL/users/x",
            "/api/Internal/users/x",
            // repeated slashes and backslashes
            "//api//internal//users/x",
            "/api///internal/users/x",
            "/api%5Cinternal%5Cusers%5Cx",
            // dots
            "/api/./internal/users/x",
            "/api/events/../internal/users/x",
            "/api/internal/../internal/users/x",
            // matrix parameters
            "/api/internal;a=b/users/x",
            "/api;x=1/internal/users/x",
            // percent-encoding, single and double
            "/api/%69nternal/users/x",
            "/api/%49NTERNAL/users/x",
            "/api/%2569nternal/users/x",
            "/api/%252569nternal/users/x",
            "/api/internal%2Fusers/x",
            "/api%2Finternal/users/x",
            "/api/%2e/internal/users/x",
            // any depth
            "/anything/at/all/internal/x",
            // management and discovery
            "/actuator", "/actuator/health", "/actuator/env", "/actuator/gateway/routes",
            "/ACTUATOR/env", "/%61ctuator/env", "/eureka/apps", "/eureka", "/api/../actuator/env");

    private static final List<String> OPEN_PATHS = List.of(
            "/api/events/e1", "/api/events", "/api/tickets/types/event/e1", "/api/media/upload",
            "/api/auth/login", "/api/auth/register/start",
            "/api/payments/vnpay/ipn", "/api/payments/momo/ipn", "/api/payments/zalopay/ipn", "/api/payments/sepay/webhook",
            "/ws/websocket", "/ws-notifications/websocket", "/api/customer/chat-search",
            "/api/events/internal-announcements");

    // --- pure path normalisation -------------------------------------------------

    @Test
    void everyVariantOfAnInternalOrManagementPathIsBlocked() {
        for (String path : INTERNAL_PATHS) {
            assertThat(InternalPathBlockFilter.isBlocked(path)).as(path).isTrue();
        }
    }

    @Test
    void normalPublicPathsAreNotBlocked() {
        for (String path : OPEN_PATHS) {
            assertThat(InternalPathBlockFilter.isBlocked(path)).as(path).isFalse();
        }
        assertThat(InternalPathBlockFilter.isBlocked("/api/seats/hold")).isFalse(); // needs a login (GatewayAuthorizationTest) but is not an internal path
        assertThat(InternalPathBlockFilter.isBlocked("/")).isFalse();
        assertThat(InternalPathBlockFilter.isBlocked("")).isFalse();
        assertThat(InternalPathBlockFilter.isBlocked(null)).isFalse();
    }

    @Test
    void rawBackslashesAreNormalisedLikeSlashes() {
        assertThat(InternalPathBlockFilter.isBlocked("/api\\internal\\users\\x")).isTrue();
        assertThat(InternalPathBlockFilter.isBlocked("\\api\\events\\e1")).isFalse();
    }

    @Test
    void malformedPercentEncodingIsRejectedRatherThanPassedOn() {
        assertThat(InternalPathBlockFilter.isBlocked("/api/%ZZ/users")).isTrue();
        assertThat(InternalPathBlockFilter.isBlocked("/api/users/%")).isTrue();
    }

    // --- whole gateway filter chain ---------------------------------------------

    @Test
    void blockedPathsNeverReachAnyHandlerWithoutATokenOrWithACustomerToken() {
        try (var context = new AnnotationConfigApplicationContext(WithFilter.class, CatchAll.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            CatchAll catchAll = context.getBean(CatchAll.class);
            for (String path : INTERNAL_PATHS) {
                client.get().uri(URI.create(path)).exchange().expectStatus().isNotFound();
                client.post().uri(URI.create(path)).exchange().expectStatus().isNotFound();
                client.put().uri(URI.create(path)).exchange().expectStatus().isNotFound();
                client.get().uri(URI.create(path)).headers(h -> h.setBearerAuth("customer")).exchange().expectStatus().isNotFound();
                client.put().uri(URI.create(path)).headers(h -> h.setBearerAuth("admin")).exchange().expectStatus().isNotFound();
            }
            assertThat(catchAll.hits.get()).as("handler invocations for blocked paths").isZero();
        }
    }

    @Test
    void publicAndPaymentCallbackPathsStillGoThrough() {
        try (var context = new AnnotationConfigApplicationContext(WithFilter.class, CatchAll.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            for (String path : OPEN_PATHS) {
                client.get().uri(URI.create(path)).exchange().expectStatus().isOk();
            }
            client.post().uri("/api/payments/sepay/webhook").exchange().expectStatus().isOk();
            // a CORS preflight is not swallowed by the internal-path filter (the real preflight is checked against a running gateway)
            client.options().uri("/api/events/e1").header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "GET").exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(404));
        }
    }

    @Test
    void authenticatedRulesAreUnchanged() {
        try (var context = new AnnotationConfigApplicationContext(WithFilter.class, CatchAll.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            for (String path : List.of("/api/users/me", "/api/bookings", "/api/payments/verify/1", "/api/notifications/users/u1", "/api/marketplace/gifts")) {
                client.get().uri(path).exchange().expectStatus().isUnauthorized();
                client.get().uri(path).headers(h -> h.setBearerAuth("customer")).exchange().expectStatus().isOk();
            }
        }
    }

    // --- second layer: the security rules alone ---------------------------------

    @Test
    void securityRulesAloneDenyInternalAndManagementPathsEvenWhenTheFilterIsMissing() {
        try (var context = new AnnotationConfigApplicationContext(WithoutFilter.class, CatchAll.class)) {
            var client = WebTestClient.bindToApplicationContext(context).build();
            CatchAll catchAll = context.getBean(CatchAll.class);
            for (String path : List.of("/api/internal/users/x", "/user-service/api/internal/users/x", "/actuator/env", "/eureka/apps")) {
                client.get().uri(path).exchange().expectStatus().isUnauthorized();
                client.get().uri(path).headers(h -> h.setBearerAuth("customer")).exchange().expectStatus().isForbidden();
                client.put().uri(path).headers(h -> h.setBearerAuth("admin")).exchange().expectStatus().isForbidden();
            }
            assertThat(catchAll.hits.get()).isZero();
        }
    }

    // --- configuration ---------------------------------------------------------------

    private static StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        for (PropertySource<?> source : sources) env.getPropertySources().addLast(source);
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return env;
    }

    @Test
    void discoveryLocatorIsOffByDefaultAndOnlyOnWhenExplicitlyEnabled() throws Exception {
        String key = "spring.cloud.gateway.discovery.locator.enabled";
        assertThat(environment(Map.of()).getProperty(key)).isEqualTo("false");
        assertThat(environment(Map.of("GATEWAY_DISCOVERY_LOCATOR_ENABLED", "true")).getProperty(key)).isEqualTo("true");
    }

    @Test
    void everyPathTheClientsUseHasAnExplicitRoute() throws Exception {
        StandardEnvironment env = environment(Map.of());
        List<PathPattern> patterns = new ArrayList<>();
        PathPatternParser parser = new PathPatternParser();
        for (int i = 0; env.getProperty("spring.cloud.gateway.routes[" + i + "].id") != null; i++) {
            for (int p = 0; env.getProperty("spring.cloud.gateway.routes[" + i + "].predicates[" + p + "]") != null; p++) {
                String predicate = env.getProperty("spring.cloud.gateway.routes[" + i + "].predicates[" + p + "]");
                if (predicate.startsWith("Path=")) {
                    for (String pattern : predicate.substring(5).split(",")) patterns.add(parser.parse(pattern.trim()));
                }
            }
        }
        assertThat(patterns).isNotEmpty();
        List<String> clientPaths = List.of(
                "/api/auth/login", "/api/auth/register/start", "/api/auth/register/verify", "/api/auth/password/forgot",
                "/api/users/me", "/api/users/me/ekyc", "/api/events", "/api/events/e1", "/api/events/venues",
                "/api/tickets/types/event/e1", "/api/tickets/seat-status", "/api/tickets/checkin",
                "/api/media/upload", "/api/bookings", "/api/bookings/by-code/c1", "/api/seats/hold",
                "/api/payments/vnpay/ipn", "/api/payments/momo/ipn", "/api/payments/zalopay/ipn", "/api/payments/sepay/webhook",
                "/api/payments/verify/b1", "/api/payouts/admin/all", "/api/notifications/users/u1",
                "/ws/websocket", "/ws/info", "/ws-notifications/websocket",
                "/api/marketplace/listings", "/api/gifts/accept",
                "/api/customer/chat-search", "/api/organizer/agent-chat", "/api/admin/risk-scoring");
        for (String path : clientPaths) {
            boolean routed = patterns.stream().anyMatch(pattern -> pattern.matches(org.springframework.http.server.PathContainer.parsePath(path)));
            assertThat(routed).as("route for " + path).isTrue();
        }
        for (String path : List.of("/user-service/api/users/me", "/booking-service/api/bookings", "/khong-ton-tai")) {
            boolean routed = patterns.stream().anyMatch(pattern -> pattern.matches(org.springframework.http.server.PathContainer.parsePath(path)));
            assertThat(routed).as("no route for " + path).isFalse();
        }
    }

    // --- test fixtures -------------------------------------------------------------------

    @RestController
    static class CatchAll {
        final AtomicInteger hits = new AtomicInteger();

        @RequestMapping("/**")
        String any() {
            hits.incrementAndGet();
            return "ok";
        }
    }

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class WithoutFilter {
        @Bean
        SecurityWebFilterChain security(ServerHttpSecurity http) {
            var config = new SecurityConfig() {
                @Override
                ReactiveJwtDecoder jwtDecoder() {
                    return value -> Mono.just(Jwt.withTokenValue(value).header("alg", "none").subject("test")
                            .claim("realm_access", Map.of("roles", List.of("admin".equals(value) ? "ADMIN" : "CUSTOMER"))).build());
                }
            };
            ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
            return config.springSecurityFilterChain(http);
        }
    }

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class WithFilter extends WithoutFilter {
        @Bean
        InternalPathBlockFilter internalPathBlockFilter() {
            return new InternalPathBlockFilter();
        }
    }
}
