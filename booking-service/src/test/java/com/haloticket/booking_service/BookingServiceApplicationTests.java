package com.haloticket.booking_service;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// chạy được khi không có Postgres, Redis, Keycloak, Eureka: dùng H2 trong bộ nhớ, giả lập Redisson và tắt Eureka
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:booking_ctx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"eureka.client.enabled=false",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class BookingServiceApplicationTests {

	// RedissonConfig kết nối Redis ngay khi khởi tạo bean nên phải thay bằng mock
	@MockitoBean
	private RedissonClient redissonClient;

	// container lắng nghe key hết hạn cũng mở kết nối Redis ngay khi khởi động
	@MockitoBean
	private RedisMessageListenerContainer keyExpirationListenerContainer;

	@Test
	void contextLoads() {
	}

}
