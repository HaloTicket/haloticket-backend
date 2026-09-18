package com.haloticket.marketplace_service.dto;

import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;

public record ResalePurchaseStatusResponse(String transactionId, MarketplaceTransactionStatus status, String sagaId) {}
