package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.EkycVerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

public record EkycStatusResponse(
        EkycVerificationStatus status,
        String legalName,
        String documentNumber,
        Instant verifiedAt,
        Long identityVersion,
        String reasonCode,
        String birthDate,
        String address,
        LocalDate documentExpiresOn
) {
    public EkycStatusResponse(
            EkycVerificationStatus status,
            String legalName,
            String documentNumber,
            Instant verifiedAt,
            Long identityVersion,
            String reasonCode
    ) {
        this(status, legalName, documentNumber, verifiedAt, identityVersion, reasonCode, null, null, null);
    }
}

