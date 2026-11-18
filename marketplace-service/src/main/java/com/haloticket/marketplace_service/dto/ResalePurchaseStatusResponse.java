package com.haloticket.marketplace_service.dto;

import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;

/** Trạng thái giao dịch mua vé bán lại và saga liên quan. */
public record ResalePurchaseStatusResponse(String transactionId, MarketplaceTransactionStatus status, String sagaId) {}
