package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.EkycVerificationStatus;

import java.time.Instant;

public record EkycStatusResponse(
        EkycVerificationStatus status,
        String legalName,
        String documentNumber,
        Instant verifiedAt,
        Long identityVersion,
        String reasonCode
) {}
