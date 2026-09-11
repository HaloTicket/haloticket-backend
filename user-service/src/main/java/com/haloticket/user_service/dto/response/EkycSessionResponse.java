package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.EkycAttemptStatus;

import java.time.Instant;

public record EkycSessionResponse(
        String sessionId,
        EkycAttemptStatus status,
        Instant expiresAt
) {}
