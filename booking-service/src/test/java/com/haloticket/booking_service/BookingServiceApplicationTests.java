package com.haloticket.booking_service;

import com.haloticket.booking_service.controller.NotificationBuyerController;
import com.haloticket.booking_service.exception.GlobalExceptionHandler;
import com.haloticket.booking_service.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

	@Autowired private BookingRepository bookings;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	@Transactional
	void paidBuyerEndpointRequiresTokenAndPagesDistinctEligibleAccounts() throws Exception {
		insertBooking("1", "event", "buyer-a", "PAID", "2026-11-26T09:00:00");
		insertBooking("2", "event", "buyer-a", "CONFIRMED", "2026-11-26T09:01:00");
		insertBooking("3", "event", "buyer-b", "CONFIRMED", "2026-11-26T10:00:00");
		insertBooking("4", "event", "buyer-c", "PAID", "2026-11-26T10:01:00");
		insertBooking("5", "event", "buyer-d", "PENDING", "2026-11-26T09:00:00");
		insertBooking("6", "event", "buyer-e", "CANCELLED", "2026-11-26T09:00:00");
		insertBooking("7", "other-event", "buyer-f", "PAID", "2026-11-26T09:00:00");
		insertBooking("8", "event", "buyer-g", "REFUNDED", "2026-11-26T09:00:00");
		var mvc = MockMvcBuilders.standaloneSetup(new NotificationBuyerController(bookings, "test-token"))
				.setControllerAdvice(new GlobalExceptionHandler()).build();
		String path = "/api/internal/bookings/events/event/notification-buyers";
		String cutoff = "2026-11-26T03:00:00Z";
		mvc.perform(get(path).param("cutoff", cutoff).header("X-Internal-Token", "wrong"))
				.andExpect(status().isForbidden());
		mvc.perform(get(path).param("cutoff", cutoff).param("limit", "201").header("X-Internal-Token", "test-token"))
				.andExpect(status().isBadRequest());
		mvc.perform(get(path).param("cutoff", cutoff).param("limit", "1").header("X-Internal-Token", "test-token"))
				.andExpect(status().isOk()).andExpect(content().json("[\"buyer-a\"]"));
		mvc.perform(get(path).param("cutoff", cutoff).param("after", "buyer-a").header("X-Internal-Token", "test-token"))
				.andExpect(status().isOk()).andExpect(content().json("[\"buyer-b\"]"));
	}

	private void insertBooking(String id, String eventId, String buyerId, String status, String updatedAt) {
		jdbc.update("INSERT INTO bookings (id, booking_code, event_id, customer_id, status, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
				id, "booking-" + id, eventId, buyerId, status, LocalDateTime.parse(updatedAt));
	}

}
