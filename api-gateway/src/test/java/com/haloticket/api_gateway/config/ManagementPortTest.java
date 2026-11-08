package com.haloticket.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Actuator is readable only on the management port (not published); the public port keeps refusing it. */
class ManagementPortTest {

    private static final int MANAGEMENT = 9091;

    private static boolean passes(String path, int localPort, int configuredManagementPort) {
        InternalPathBlockFilter filter = new InternalPathBlockFilter();
        ReflectionTestUtils.setField(filter, "managementPort", configuredManagementPort);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).localAddress(new InetSocketAddress(localPort)));
        AtomicBoolean reached = new AtomicBoolean(false);
        WebFilterChain chain = e -> {
            reached.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        if (!reached.get()) {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        return reached.get();
    }

    @Test
    void actuatorIsReachableOnTheManagementPortOnly() {
        assertThat(passes("/actuator/metrics/process.uptime", MANAGEMENT, MANAGEMENT)).isTrue();
        assertThat(passes("/actuator/health", MANAGEMENT, MANAGEMENT)).isTrue();
        assertThat(passes("/actuator/health", 8888, MANAGEMENT)).as("public port").isFalse();
        assertThat(passes("/actuator/env", 8888, MANAGEMENT)).as("public port").isFalse();
    }

    @Test
    void withoutAManagementPortNothingIsOpened() {
        assertThat(passes("/actuator/health", MANAGEMENT, 0)).isFalse();
        assertThat(passes("/actuator/health", 0, 0)).isFalse();
    }

    @Test
    void internalAndTrickyPathsAreStillBlockedOnTheManagementPort() {
        assertThat(passes("/api/internal/users/x", MANAGEMENT, MANAGEMENT)).isFalse();
        assertThat(passes("/eureka/apps", MANAGEMENT, MANAGEMENT)).isFalse();
        assertThat(passes("/actuator/../api/internal/x", MANAGEMENT, MANAGEMENT)).isFalse();
        assertThat(passes("/%61ctuator/env", MANAGEMENT, MANAGEMENT)).isFalse();
        assertThat(passes("/actuator;x=1/env", MANAGEMENT, MANAGEMENT)).isFalse();
    }

    @Test
    void theHelpersAreStrict() {
        assertThat(InternalPathBlockFilter.arrivedOnManagementPort(null, MANAGEMENT)).isFalse();
        assertThat(InternalPathBlockFilter.arrivedOnManagementPort(new InetSocketAddress(MANAGEMENT), -1)).isFalse();
        assertThat(InternalPathBlockFilter.isActuatorPath("/actuator")).isTrue();
        assertThat(InternalPathBlockFilter.isActuatorPath("/actuatorx")).isFalse();
        assertThat(InternalPathBlockFilter.isActuatorPath("/api/actuator/x")).isFalse();
    }
}
