package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @Test
    void allowsConfiguredOriginAndRejectsOtherOrigins() {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://tickets.example.test, http://localhost:3000 ");
        var filter = config.corsWebFilter();
        var allowed = preflight("https://tickets.example.test");
        filter.filter(allowed, exchange -> Mono.empty()).block();
        assertThat(allowed.getResponse().getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://tickets.example.test");
        assertThat(allowed.getResponse().getHeaders().getAccessControlAllowCredentials()).isTrue();
        var denied = preflight("https://untrusted.example.test");
        filter.filter(denied, exchange -> Mono.empty()).block();
        assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private MockServerWebExchange preflight(String origin) {
        return MockServerWebExchange.from(MockServerHttpRequest.options("http://localhost/api/events")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
    }
}
