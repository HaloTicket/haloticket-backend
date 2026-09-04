package com.haloticket.user_service.auth.service;

import com.haloticket.user_service.auth.domain.OtpPurpose;
import java.time.Instant;

public interface OtpDeliveryPort {
    void send(String email, OtpPurpose purpose, String otp, Instant expiresAt);
}
