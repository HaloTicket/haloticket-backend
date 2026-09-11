package com.haloticket.event_service.dto.response;

public record OrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
