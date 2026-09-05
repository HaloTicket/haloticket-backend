package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.OtpPurpose;

public record OtpChallengeCommand(
        OtpPurpose purpose,
        String subject,
        String idempotencyKey,
        String requestIpHash
) {
    public OtpChallengeCommand {
        if (purpose == null) throw new IllegalArgumentException("OTP purpose is required");
        subject = normalize(subject, 320, "OTP subject");
        idempotencyKey = normalize(idempotencyKey, 128, "OTP idempotency key");
        requestIpHash = requestIpHash == null ? null : requestIpHash.trim();
    }

    private static String normalize(String value, int max, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
}
