package com.haloticket.marketplace_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrganizerListingResponse(
    String listingId,
    String refCode,
    String ticketTypeName,
    String seatCode,
    String sellerEmailMasked,
    BigDecimal price,
    String currency,
    String status,
    Instant createdAt,
    int quantity,
    List<String> ticketIds,
    BigDecimal originalPrice,
    BigDecimal minPriceSnapshot,
    BigDecimal maxPriceSnapshot,
    BigDecimal serviceFeePercent,
    String moderationReason
) {
    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status,
                                    Instant createdAt, int quantity, List<String> ticketIds, BigDecimal originalPrice,
                                    BigDecimal minPriceSnapshot, BigDecimal maxPriceSnapshot) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt,
                quantity, ticketIds, originalPrice, minPriceSnapshot, maxPriceSnapshot, null, null);
    }

    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status, Instant createdAt) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt, 1, List.of(), null, null, null, null, null);
    }

    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status,
                                    Instant createdAt, int quantity, List<String> ticketIds) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt, quantity, ticketIds, null, null, null, null, null);
    }
}
