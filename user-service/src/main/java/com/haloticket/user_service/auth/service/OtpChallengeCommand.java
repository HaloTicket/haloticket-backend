package com.haloticket.user_service.auth.service;

import com.haloticket.user_service.auth.domain.OtpPurpose;

public record OtpChallengeCommand(
        OtpPurpose purpose,
        String subject,
        String idempotencyKey,
        String requestIpHash
) {
}
