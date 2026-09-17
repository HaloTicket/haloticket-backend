package com.haloticket.marketplace_service.dto;

import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record ReservationResponse(String transactionId, String listingId, MarketplaceTransactionStatus status,
                                  BigDecimal amount, String currency, Instant reservationExpiresAt) {}
