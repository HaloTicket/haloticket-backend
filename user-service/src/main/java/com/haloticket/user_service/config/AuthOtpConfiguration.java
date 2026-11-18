package com.haloticket.user_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import com.haloticket.user_service.service.OtpGenerator;
import com.haloticket.user_service.service.OtpHasher;
import com.haloticket.user_service.service.OtpRateLimiter;

/** Khai báo các bean phục vụ OTP: đồng hồ, bộ sinh mã, bộ băm và bộ giới hạn tần suất gửi. */
@Configuration
public class AuthOtpConfiguration {
    /** Đồng hồ UTC dùng cho thời hạn OTP, tách riêng để kiểm thử thay thế được. */
    @Bean
    public Clock authOtpClock() {
        return Clock.systemUTC();
    }

    /** Bộ sinh mã OTP. */
    @Bean
    public OtpGenerator otpGenerator() {
        return new OtpGenerator();
    }

    /**
     * Bộ băm OTP có pepper lấy từ {@code auth.otp.pepper} hoặc biến môi trường {@code AUTH_OTP_PEPPER}.
     *
     * @param pepper chuỗi bí mật trộn vào khi băm
     */
    @Bean
    public OtpHasher otpHasher(@Value("${auth.otp.pepper:${AUTH_OTP_PEPPER:}}") String pepper) {
        return new OtpHasher(pepper);
    }

    /** Bộ giới hạn gửi OTP: tối đa 10 lần mỗi đối tượng nhận và 10 lần mỗi IP trong một giờ. */
    @Bean
    public OtpRateLimiter otpRateLimiter() {
        return new OtpRateLimiter(10, 10);
    }
}
