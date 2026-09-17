package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.enums.SagaState;
public record SagaResponse(String sagaId, SagaState state, String listingId, String errorCode) {}
