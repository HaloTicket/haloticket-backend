package com.haloticket.notification_service.auth;

import java.time.Instant;

/**
 * Sự kiện OTP do dịch vụ xác thực phát lên Kafka: email nhận, mục đích ({@code PASSWORD_RESET} hoặc đăng ký),
 * mã OTP 6 chữ số và thời điểm hết hạn.
 */
public record AuthOtpEvent(String email, String purpose, String otp, Instant expiresAt) {
}
