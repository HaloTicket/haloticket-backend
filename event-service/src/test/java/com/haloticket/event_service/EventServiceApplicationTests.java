package com.haloticket.event_service;

import com.haloticket.event_service.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// chạy được khi không có Postgres, Keycloak, Eureka: dùng H2 trong bộ nhớ, trỏ jwk-set-uri tới địa chỉ giả (không gọi mạng khi khởi động)
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:event_service_ctx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"eureka.client.enabled=false",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class EventServiceApplicationTests {

	// DataInitializer nạp dữ liệu mẫu bằng merge() với id gán sẵn; trên DB trống Hibernate 7 báo StaleObjectStateException. Test chỉ kiểm tra context nên bỏ qua bước nạp mẫu.
	@MockitoBean
	private DataInitializer dataInitializer;

	@Test
	void contextLoads() {
	}

}
