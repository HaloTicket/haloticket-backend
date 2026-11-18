package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.OtpPurpose;
import java.time.Instant;

/** Cổng gửi mã OTP tới người dùng. */
public interface OtpDeliveryPort {
    /** Gửi mã OTP tới email, kèm mục đích và thời điểm hết hạn. */
    void send(String email, OtpPurpose purpose, String otp, Instant expiresAt);
}
