package com.haloticket.notification_service.auth;

import java.time.Instant;

public record AuthOtpEvent(String email, String purpose, String otp, Instant expiresAt) {
}
