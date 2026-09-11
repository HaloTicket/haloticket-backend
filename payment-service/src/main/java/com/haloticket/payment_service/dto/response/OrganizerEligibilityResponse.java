package com.haloticket.payment_service.dto.response;

public record OrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
