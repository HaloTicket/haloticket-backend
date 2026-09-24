package com.haloticket.marketplace_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrganizerListingResponse(
    String listingId,
    String refCode,
    String ticketTypeName,
    String seatCode,
    String sellerEmailMasked,
    BigDecimal price,
    String currency,
    String status,
    Instant createdAt
) {}
