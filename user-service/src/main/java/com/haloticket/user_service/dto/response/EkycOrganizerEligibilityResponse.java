package com.haloticket.user_service.dto.response;

public record EkycOrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
