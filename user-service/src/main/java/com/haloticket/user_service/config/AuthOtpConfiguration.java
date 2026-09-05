package com.haloticket.user_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import com.haloticket.user_service.service.OtpGenerator;
import com.haloticket.user_service.service.OtpHasher;
import com.haloticket.user_service.service.OtpRateLimiter;

@Configuration
public class AuthOtpConfiguration {
    @Bean
    public Clock authOtpClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OtpGenerator otpGenerator() {
        return new OtpGenerator();
    }

    @Bean
    public OtpHasher otpHasher(@Value("${auth.otp.pepper:${AUTH_OTP_PEPPER:}}") String pepper) {
        return new OtpHasher(pepper);
    }

    @Bean
    public OtpRateLimiter otpRateLimiter() {
        return new OtpRateLimiter(10, 10);
    }
}
