package com.haloticket.ticket_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// chạy được khi không có Postgres, Keycloak, Eureka: dùng H2 trong bộ nhớ, trỏ jwk-set-uri tới địa chỉ giả (không gọi mạng khi khởi động)
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ticket_service_ctx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"eureka.client.enabled=false",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class TicketServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
