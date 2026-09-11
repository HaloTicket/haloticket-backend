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

@SpringBootTest(properties = "auth.otp.pepper=test-only-otp-pepper")
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
				.isActive(true)
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
