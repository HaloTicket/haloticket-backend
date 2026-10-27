package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may reach which gateway path. Tokens carry the claims the real Keycloak tokens carry (roles and
 * realm_access.roles, for example [ROLE_USER, ROLE_ADMIN]). The downstream service is a stub answering 200, so a
 * 401 or 403 here can only come from the gateway.
 */
class GatewayAuthorizationTest {

    private static final String ANON = "";
    private static final List<String> LOGGED_IN = List.of("customer", "event-staff", "admin");

    /** admin only: 401 without a token, 403 for everybody else, 200 for ROLE_ADMIN */
    private static final List<Route> ADMIN_ONLY = List.of(
            new Route("GET", "/api/admin/system-health"),
            new Route("GET", "/api/admin/system-logs"),
            new Route("GET", "/api/admin/ssl-status"),
            new Route("POST", "/api/admin/risk-scoring"),
            new Route("POST", "/api/admin/assistant-chat"),
            // the AI routes that read event data through the language model: admin only in phase 1 of the lock-down
            new Route("POST", "/api/organizer/data-analysis"),
            new Route("POST", "/api/organizer/voucher-strategy"),
            new Route("POST", "/api/organizer/agent-chat"),
            new Route("GET", "/api/payouts/admin/all"),
            new Route("POST", "/api/payouts/admin/00000000-0000-0000-0000-000000000000/approve"),
            new Route("GET", "/api/users/admin"),
            new Route("GET", "/api/users/admin/some-id"),
            new Route("PUT", "/api/users/admin/some-id/roles"),
            new Route("PUT", "/api/users/admin/some-id/status"));

    /** a login is enough, no role: 401 without a token, 200 for any valid token (event staff may hold only ROLE_USER) */
    private static final List<Route> LOGIN_REQUIRED = List.of(
            new Route("POST", "/api/organizer/generate-event-content"),
            new Route("POST", "/api/seats/hold"),
            new Route("POST", "/api/seats/release"),
            new Route("GET", "/api/gifts/anything"),
            new Route("GET", "/api/payouts/organizer"),
            new Route("POST", "/api/payouts/organizer/request"),
            new Route("GET", "/api/notifications/users/00000000-0000-0000-0000-000000000000"),
            new Route("POST", "/api/notifications/publish"),
            new Route("GET", "/api/bookings/my"),
            new Route("GET", "/api/bookings/events/some-event/organizer"),
            new Route("POST", "/api/bookings"),
            new Route("GET", "/api/users/me"),
            new Route("POST", "/api/payments"),
            new Route("GET", "/api/payments/organizer-payout-account/some-user"),
            new Route("POST", "/api/payments/organizer-payout-account"),
            new Route("GET", "/api/events/my-events"),
            new Route("GET", "/api/events/some-event/analytics"),
            new Route("POST", "/api/events"),
            new Route("PUT", "/api/events/some-event"),
            new Route("POST", "/api/events/some-event/members"),
            new Route("GET", "/api/events/some-event/members"),
            new Route("POST", "/api/events/some-event/vouchers"),
            new Route("PUT", "/api/events/admin/config/system-config"),
            new Route("PUT", "/api/events/admin/config/homepage/banners"),
            new Route("POST", "/api/events/categories"),
            new Route("POST", "/api/events/venues"),
            new Route("DELETE", "/api/events/vouchers/some-voucher"),
            new Route("POST", "/api/tickets/checkin"),
            new Route("GET", "/api/tickets/checkin/events/some-event"),
            new Route("POST", "/api/tickets/types"),
            new Route("POST", "/api/tickets/types/sync/some-event"),
            new Route("PUT", "/api/tickets/seat-layouts/event/some-event/draft"),
            new Route("POST", "/api/tickets/seat-layouts/event/some-event/publish"),
            new Route("POST", "/api/media/upload"),
            new Route("POST", "/api/media/upload-base64"),
            new Route("DELETE", "/api/media/delete"),
            new Route("GET", "/api/marketplace/listings/me"),
            new Route("POST", "/api/marketplace/gifts"),
            new Route("GET", "/api/some-path-no-rule-names"));

    /** open to anyone: the web and mobile apps call these without a token */
    private static final List<Route> PUBLIC = List.of(
            new Route("POST", "/api/customer/chat-search"),
            new Route("POST", "/api/customer/smart-booking-chat"),
            new Route("POST", "/api/auth/login"),
            new Route("POST", "/api/auth/register/start"),
            new Route("POST", "/api/auth/register/verify"),
            new Route("POST", "/api/auth/forgot-password"),
            new Route("POST", "/api/auth/reset-password"),
            new Route("GET", "/api/events"),
            new Route("GET", "/api/events/some-event"),
            new Route("GET", "/api/events/categories"),
            new Route("GET", "/api/events/venues"),
            new Route("GET", "/api/events/trending-searches"),
            new Route("GET", "/api/events/some-event/vouchers"),
            new Route("GET", "/api/events/admin/config/homepage/banners"),
            new Route("POST", "/api/events/some-event/view"),
            new Route("POST", "/api/events/search-log"),
            new Route("GET", "/api/tickets/types/event/some-event"),
            new Route("GET", "/api/tickets/types/showtime/some-showtime"),
            new Route("GET", "/api/tickets/seat-layouts/event/some-event"),
            new Route("GET", "/api/tickets/seat-status"),
            // GET /api/tickets/** other than check-in passes the gateway; ticket-service asks for the login itself
            new Route("GET", "/api/tickets/mine"),
            new Route("GET", "/api/media/some-file"),
            new Route("GET", "/api/marketplace/listings"),
            new Route("GET", "/api/marketplace/events"),
            new Route("GET", "/api/v1/event/something"),
            new Route("GET", "/ws/info"),
            new Route("GET", "/ws/websocket"),
            new Route("GET", "/ws-notifications/info"),
            new Route("GET", "/ws-notifications/websocket"),
            // payment provider callbacks carry no token
            new Route("GET", "/api/payments/vnpay/ipn"),
            new Route("POST", "/api/payments/momo/ipn"),
            new Route("POST", "/api/payments/zalopay/ipn"),
            new Route("POST", "/api/payments/sepay/webhook"));

    record Route(String method, String path) {
        @Override public String toString() { return method + " " + path; }
    }

    @Test
    void adminAreasNeedTheAdminRole() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (Route route : ADMIN_ONLY) {
                assertThat(status(client, route, ANON)).as("anonymous " + route).isEqualTo(401);
                assertThat(status(client, route, "customer")).as("customer " + route).isEqualTo(403);
                assertThat(status(client, route, "event-staff")).as("event staff " + route).isEqualTo(403);
                assertThat(status(client, route, "admin")).as("admin " + route).isEqualTo(200);
            }
        }
    }

    @Test
    void loginRequiredPathsRejectAnonymousAndAcceptAnyLoggedInUserWhateverTheRole() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (Route route : LOGIN_REQUIRED) {
                assertThat(status(client, route, ANON)).as("anonymous " + route).isEqualTo(401);
                for (String user : LOGGED_IN) {
                    assertThat(status(client, route, user)).as(user + " " + route).isEqualTo(200);
                }
            }
        }
    }

    @Test
    void publicPathsStayOpenWithoutAToken() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (Route route : PUBLIC) {
                assertThat(status(client, route, ANON)).as("anonymous " + route).isEqualTo(200);
                assertThat(status(client, route, "customer")).as("customer " + route).isEqualTo(200);
            }
        }
    }

    @Test
    void anonymousChatbotStaysPublicForEveryChatbotRoute() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            for (String path : List.of("/api/customer/chat-search", "/api/customer/smart-booking-chat", "/api/customer/anything-else")) {
                assertThat(status(client, new Route("POST", path), ANON)).as("anonymous " + path).isEqualTo(200);
            }
        }
    }

    @Test
    void preflightRequestsPassWithoutAToken() {
        try (var context = context(true)) {
            // CORS needs an absolute request URL, so this client carries a base URL
            WebTestClient client = WebTestClient.bindToApplicationContext(context).configureClient().baseUrl("http://localhost").build();
            for (String path : List.of("/api/admin/system-health", "/api/organizer/data-analysis", "/api/seats/hold",
                    "/api/payouts/admin/all", "/api/users/admin", "/api/tickets/types", "/api/events/some-event")) {
                client.options().uri(path)
                        .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                        .exchange().expectStatus().isOk();
            }
        }
    }

    @Test
    void serviceToServicePathsStayClosedInBothModes() {
        for (boolean enabled : List.of(true, false)) {
            try (var context = context(enabled)) {
                WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
                for (String user : List.of(ANON, "customer", "admin")) {
                    for (String path : List.of("/api/internal/users/x", "/actuator/health", "/eureka/apps")) {
                        assertThat(status(client, new Route("GET", path), user)).as("enabled=" + enabled + " " + user + " " + path).isIn(401, 403);
                    }
                }
            }
        }
    }

    @Test
    void reportAdminRuleDoesNotDependOnTheFlag() {
        try (var context = context(false)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Route report = new Route("GET", "/api/events/admin/reports");
            assertThat(status(client, report, ANON)).isEqualTo(401);
            assertThat(status(client, report, "customer")).isEqualTo(403);
            assertThat(status(client, report, "admin")).isEqualTo(200);
        }
    }

    @Test
    void withTheFlagOffTheOldBehaviourReturns() {
        try (var context = context(false)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            List<Route> openBefore = new ArrayList<>(ADMIN_ONLY);
            openBefore.addAll(List.of(new Route("POST", "/api/organizer/data-analysis"), new Route("POST", "/api/seats/hold"),
                    new Route("GET", "/api/gifts/anything"), new Route("POST", "/api/customer/chat-search"),
                    new Route("POST", "/api/tickets/types"), new Route("POST", "/api/media/upload"),
                    new Route("GET", "/api/some-path-no-rule-names")));
            for (Route route : openBefore) {
                // /api/users/admin was already behind the login rule for /api/users/**
                int expected = route.path().startsWith("/api/users/") ? 401 : 200;
                assertThat(status(client, route, ANON)).as("anonymous " + route).isEqualTo(expected);
            }
            for (Route route : List.of(new Route("GET", "/api/bookings/my"), new Route("GET", "/api/users/me"),
                    new Route("GET", "/api/notifications/users/x"), new Route("POST", "/api/payments"))) {
                assertThat(status(client, route, ANON)).as("anonymous " + route).isEqualTo(401);
            }
        }
    }

    @Test
    void contentGenerationNeedsOnlyALoginWhileTheAnalysisRoutesNeedAdmin() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Route generate = new Route("POST", "/api/organizer/generate-event-content");
            assertThat(status(client, generate, ANON)).isEqualTo(401);
            assertThat(status(client, generate, "customer")).isEqualTo(200);
            for (String path : List.of("/api/organizer/data-analysis", "/api/organizer/voucher-strategy", "/api/organizer/agent-chat")) {
                Route route = new Route("POST", path);
                assertThat(status(client, route, ANON)).as("anonymous " + path).isEqualTo(401);
                assertThat(status(client, route, "customer")).as("customer " + path).isEqualTo(403);
                assertThat(status(client, route, "event-staff")).as("event staff " + path).isEqualTo(403);
                assertThat(status(client, route, "admin")).as("admin " + path).isEqualTo(200);
            }
        }
    }

    @Test
    void theAnalysisRoutesFallBackToAnyLoginWhenTheirOwnFlagIsOff() {
        TestConfiguration.aiAnalysisAdminOnly = false;
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Route route = new Route("POST", "/api/organizer/data-analysis");
            assertThat(status(client, route, ANON)).isEqualTo(401);
            assertThat(status(client, route, "customer")).isEqualTo(200);
            // the other admin areas are not affected by that flag
            assertThat(status(client, new Route("GET", "/api/admin/system-health"), "customer")).isEqualTo(403);
        } finally {
            TestConfiguration.aiAnalysisAdminOnly = true;
        }
    }

    @Test
    void theAdminCheckReadsBothRoleClaimsAndNothingElse() {
        try (var context = context(true)) {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Route route = new Route("GET", "/api/admin/system-health");
            assertThat(status(client, route, "admin-realm-only")).isEqualTo(200);
            assertThat(status(client, route, "admin-roles-only")).isEqualTo(200);
            assertThat(status(client, route, "admin-lookalike")).as("an ADMIN-looking scope or username is not a role").isEqualTo(403);
        }
    }

    private static int status(WebTestClient client, Route route, String token) {
        var request = client.method(HttpMethod.valueOf(route.method())).uri(route.path());
        if (!token.isEmpty()) request.headers(h -> h.setBearerAuth(token));
        if (route.method().equals("POST") || route.method().equals("PUT")) request.bodyValue(Map.of());
        return request.exchange().returnResult(String.class).getStatus().value();
    }

    private static AnnotationConfigApplicationContext context(boolean authzEnabled) {
        TestConfiguration.authzEnabled = authzEnabled;
        return new AnnotationConfigApplicationContext(TestConfiguration.class, Downstream.class);
    }

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class TestConfiguration {
        static boolean authzEnabled = true;
        static boolean aiAnalysisAdminOnly = true;

        @Bean
        SecurityWebFilterChain security(ServerHttpSecurity http) {
            var config = new SecurityConfig() {
                @Override ReactiveJwtDecoder jwtDecoder() {
                    return value -> Mono.just(token(value));
                }
            };
            ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173");
            ReflectionTestUtils.setField(config, "authzEnabled", authzEnabled);
            ReflectionTestUtils.setField(config, "aiAnalysisAdminOnly", aiAnalysisAdminOnly);
            return config.springSecurityFilterChain(http);
        }

        private static Jwt token(String name) {
            List<String> roles = switch (name) {
                case "admin", "admin-realm-only", "admin-roles-only" -> List.of("ROLE_USER", "ROLE_ADMIN");
                default -> List.of("ROLE_USER"); // customers and event staff alike: staff rights live in event-service
            };
            Jwt.Builder builder = Jwt.withTokenValue(name).header("alg", "none").subject("test");
            if (!name.equals("admin-realm-only")) builder.claim("roles", roles);
            if (!name.equals("admin-roles-only")) builder.claim("realm_access", Map.of("roles", roles));
            if (name.equals("admin-lookalike")) builder.claim("scope", "ADMIN admin").claim("preferred_username", "admin");
            return builder.build();
        }
    }

    /** Stands in for every downstream service. */
    @RestController
    static class Downstream {
        @RequestMapping("/**")
        String any() { return "ok"; }
    }
}
