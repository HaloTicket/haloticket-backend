package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;
public record ListingResponse(String id, String ticketId, String sellerId, BigDecimal price, ListingStatus status, Instant expiresAt, Instant publishedAt) {
    public static ListingResponse from(TicketListing listing) { return new ListingResponse(listing.getId(), listing.getTicketId(), listing.getSellerId(), listing.getPrice(), listing.getStatus(), listing.getExpiresAt(), listing.getPublishedAt()); }
}
