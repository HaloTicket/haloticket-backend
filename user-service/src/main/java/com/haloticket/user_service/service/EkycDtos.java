package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycAttemptStatus;
import com.haloticket.user_service.enums.EkycVerificationStatus;

import java.time.Instant;

public final class EkycDtos {
    private EkycDtos() {}

    public record StartSessionRequest(boolean consentAccepted, String consentVersion) {}

    public record SessionResponse(String sessionId, EkycAttemptStatus status, Instant expiresAt) {}

    public record StatusResponse(
            EkycVerificationStatus status,
            String legalName,
            String documentNumber,
            Instant verifiedAt,
            Long identityVersion,
            String reasonCode
    ) {}

    public record OrganizerEligibility(
            boolean eligible,
            String userId,
            Long identityVersion,
            String reasonCode
    ) {}
}
