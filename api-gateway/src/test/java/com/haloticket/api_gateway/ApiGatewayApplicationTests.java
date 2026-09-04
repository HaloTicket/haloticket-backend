package com.haloticket.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.haloticket.api_gateway.config.SecurityConfig;

@WebFluxTest(controllers = ApiGatewayApplicationTests.ProbeController.class)
@Import({SecurityConfig.class, ApiGatewayApplicationTests.ProbeController.class})
class ApiGatewayApplicationTests {

	@MockBean
	JwtDecoder jwtDecoder;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void allowsUnauthenticatedRequestsToPublicApiRoutes() {
		webTestClient.get().uri("/api/events/probe").exchange().expectStatus().isOk();
		webTestClient.get().uri("/api/tickets/probe").exchange().expectStatus().isOk();
		webTestClient.get().uri("/api/marketplace/probe").exchange().expectStatus().isOk();
		webTestClient.get().uri("/api/v1/probe").exchange().expectStatus().isOk();
	}

	@Test
	void deniesUnauthenticatedRequestsToProtectedApiRoutes() {
		webTestClient.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/users/probe").exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/bookings/probe").exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/payments/probe").exchange().expectStatus().isUnauthorized();
		webTestClient.get().uri("/api/notifications/probe").exchange().expectStatus().isUnauthorized();
	}

	@RestController
	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeController {

		@GetMapping(value = {
				"/api/events/probe", "/api/tickets/probe", "/api/marketplace/probe", "/api/v1/probe",
				"/api/users/me", "/api/users/probe", "/api/bookings/probe", "/api/payments/probe", "/api/notifications/probe"
		}, produces = MediaType.TEXT_PLAIN_VALUE)
		String probe() {
			return "ok";
		}
	}

}
