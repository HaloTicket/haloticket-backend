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

}
