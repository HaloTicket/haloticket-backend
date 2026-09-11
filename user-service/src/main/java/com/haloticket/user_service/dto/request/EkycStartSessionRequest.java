package com.haloticket.user_service.dto.request;

public record EkycStartSessionRequest(
        boolean consentAccepted,
        String consentVersion
) {}
