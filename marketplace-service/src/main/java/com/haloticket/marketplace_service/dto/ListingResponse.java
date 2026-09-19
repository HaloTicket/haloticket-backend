package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;
public record ListingResponse(String id, String ticketId, String eventId, String ticketTypeName,
                              String section, String row, String seat, BigDecimal price,
                              BigDecimal originalPrice, ListingStatus status, Instant expiresAt,
                              Instant publishedAt, Integer quantity, Boolean allowRetail,
                              boolean ownedByCurrentUser) {
    public static ListingResponse from(TicketListing listing, String currentUserId) {
        return new ListingResponse(listing.getId(), listing.getTicketId(), listing.getEventId(),
                listing.getTicketTypeNameSnapshot(), listing.getSectionSnapshot(), listing.getRowSnapshot(),
                listing.getSeatSnapshot(), listing.getPrice(), listing.getOriginalPrice(), listing.getStatus(),
                listing.getExpiresAt(), listing.getPublishedAt(), listing.getQuantity(), listing.getAllowRetail(),
                currentUserId != null && currentUserId.equals(listing.getSellerId()));
    }

    public static ListingResponse from(TicketListing listing) { return from(listing, null); }
}
