package com.haloticket.user_service;

import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.OtpHasher;
import com.haloticket.user_service.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;
import com.haloticket.user_service.controller.NotificationRecipientController;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

// chạy được khi không có Postgres, Keycloak, Eureka: dùng H2 trong bộ nhớ, trỏ jwk-set-uri tới địa chỉ giả (không gọi mạng khi khởi động)
@SpringBootTest(properties = {
		"auth.otp.pepper=test-only-otp-pepper",
		"spring.datasource.url=jdbc:h2:mem:user_ctx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"eureka.client.enabled=false",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class UserServiceApplicationTests {

	@Autowired
	private OtpHasher otpHasher;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserService userService;
	@Autowired
	private EntityManager entityManager;

	@Test
	void contextLoadsWithTestOnlyOtpPepper() {
		assertThat(otpHasher.matches("123456", otpHasher.hash("123456"))).isTrue();
	}

	@Test
	@Transactional
	void profileChangesRemainInDatabaseAfterIdentitySync() {
		String identityId = "profile-test-" + UUID.randomUUID();
		String email = identityId + "@example.com";
		userRepository.saveAndFlush(User.builder()
				.keycloakId(identityId)
				.email(email)
				.fullName("Original Name")
				.status(UserStatus.ACTIVE)
				.build());

		Jwt jwt = Jwt.withTokenValue("token")
				.header("alg", "none")
				.subject(identityId)
				.claim("email", email)
				.claim("name", "Google Account Name")
				.build();
		UserProfileUpdateRequest request = new UserProfileUpdateRequest();
		request.setFullName("Name Saved From Profile");
		request.setPhoneNumber("0901234567");
		request.setGender(Gender.OTHER);
		request.setAvatarUrl("https://cdn.example.com/avatars/profile.png");

		userService.updateCurrentUser(jwt, request);
		entityManager.flush();
		entityManager.clear();

		User reloaded = userRepository.findByKeycloakId(identityId).orElseThrow();
		assertThat(reloaded.getFullName()).isEqualTo("Name Saved From Profile");
		assertThat(reloaded.getAvatarUrl()).endsWith("/avatars/profile.png");
		assertThat(userService.syncCurrentUser(jwt).getFullName()).isEqualTo("Name Saved From Profile");
	}

	@Test
	@Transactional
	void notificationDirectoryUsesActiveAccountsAndAStableCursor() {
		var controller = new NotificationRecipientController(userRepository, "test-secret");
		User alice = account("alice", UserStatus.ACTIVE);
		User bob = account("bob", UserStatus.ACTIVE);
		User banned = account("banned", UserStatus.BANNED);
		Instant cutoff = Instant.now().plusSeconds(60);
		var ordered = java.util.stream.Stream.of(alice.getId(), bob.getId()).sorted().toList();

		assertThat(controller.activeIds("test-secret", cutoff, "", 1)).containsExactly(ordered.getFirst());
		assertThat(controller.activeIds("test-secret", cutoff, ordered.getFirst(), 1)).containsExactly(ordered.getLast());
		assertThat(controller.activeIds("test-secret", Instant.EPOCH, "", 200)).isEmpty();
		assertThat(controller.activeMatches("test-secret", cutoff, java.util.List.of(alice.getId(), banned.getId())))
				.containsExactly(alice.getId());
		assertThat(controller.activeBuyerMatches("test-secret", cutoff,
				java.util.List.of(alice.getKeycloakId(), "unmatched-booking-buyer")))
				.containsExactly(alice.getId());
		assertThat(controller.activeBuyerMatches("test-secret", cutoff,
				java.util.List.of(alice.getKeycloakId(), alice.getId(), banned.getKeycloakId())))
				.containsExactly(alice.getId());
		assertThat(controller.resolve("test-secret", alice.getKeycloakId()).id()).isEqualTo(alice.getId());
		assertThatThrownBy(() -> controller.activeAccount("test-secret", banned.getId(), cutoff))
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
		assertThatThrownBy(() -> controller.activeIds(null, cutoff, "", 1))
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
	}

	private User account(String name, UserStatus status) {
		return userRepository.saveAndFlush(User.builder().keycloakId(name + "-notification-test")
				.email(name + "@notification.test").status(status).build());
	}

}
