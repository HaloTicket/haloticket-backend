package com.haloticket.user_service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AuthOtpConfiguration {
    @Bean
    Clock authOtpClock() {
        return Clock.systemUTC();
    }

    @Bean
    OtpGenerator otpGenerator() {
        return new OtpGenerator();
    }

    @Bean
    OtpHasher otpHasher(@Value("${auth.otp.pepper:${AUTH_OTP_PEPPER:}}") String pepper) {
        return new OtpHasher(pepper);
    }

    @Bean
    OtpRateLimiter otpRateLimiter() {
        return new OtpRateLimiter(10, 10);
    }
}
