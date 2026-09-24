package com.haloticket.event_service.dto.response;

public record TransferSettingsResponse(ResalePolicyResponse resale, GiftPolicyResponse gift, boolean canWrite) {}
