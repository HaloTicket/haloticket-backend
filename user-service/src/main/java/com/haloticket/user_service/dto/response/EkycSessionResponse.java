package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.EkycAttemptStatus;

import java.time.Instant;

/** Phiên eKYC vừa tạo: mã phiên, trạng thái và thời điểm hết hạn. */
public record EkycSessionResponse(
        String sessionId,
        EkycAttemptStatus status,
        Instant expiresAt
) {}
