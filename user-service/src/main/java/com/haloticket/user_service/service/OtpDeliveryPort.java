package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.OtpPurpose;
import java.time.Instant;

public interface OtpDeliveryPort {
    void send(String email, OtpPurpose purpose, String otp, Instant expiresAt);
}
