package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.enums.SagaState;
/** Trạng thái một saga marketplace: mã saga, trạng thái hiện tại, tin liên quan và mã lỗi (nếu có). */
public record SagaResponse(String sagaId, SagaState state, String listingId, String errorCode) {}
