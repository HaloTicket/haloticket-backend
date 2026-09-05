package com.haloticket.user_service;

import com.haloticket.user_service.service.OtpHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "auth.otp.pepper=test-only-otp-pepper")
class UserServiceApplicationTests {

	@Autowired
	private OtpHasher otpHasher;

	@Test
	void contextLoadsWithTestOnlyOtpPepper() {
		assertThat(otpHasher.matches("123456", otpHasher.hash("123456"))).isTrue();
	}

}
