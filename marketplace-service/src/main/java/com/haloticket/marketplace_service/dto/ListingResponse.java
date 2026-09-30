package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record ListingResponse(String id, String ticketId, String eventId, String ticketTypeName,
                              String section, String row, String seat, BigDecimal price,
                              BigDecimal originalPrice, ListingStatus status, Instant expiresAt,
                              Instant publishedAt, Integer quantity, Boolean allowRetail,
                              boolean ownedByCurrentUser, List<String> ticketIds, List<ListingTicketResponse> tickets) {
    public static ListingResponse from(TicketListing listing, String currentUserId) {
        var visible = listing.remainingMembers();
        var first = visible.isEmpty() ? listing.getMembers().get(0) : visible.get(0);
        return new ListingResponse(listing.getId(), first.getTicketId(), listing.getEventId(),
                first.getTicketTypeName(), first.getSection(), first.getRow(),
                first.getSeat(), listing.getPrice(), first.getOriginalPrice(), listing.getStatus(),
                listing.getExpiresAt(), listing.getPublishedAt(), listing.getQuantity(), listing.getAllowRetail(),
                currentUserId != null && currentUserId.equals(listing.getSellerId()),
                listing.remainingMembers().stream().map(member -> member.getTicketId()).toList(),
                listing.remainingMembers().stream().map(member -> ListingTicketResponse.from(member, listing.getPrice())).toList());
    }

    public static ListingResponse from(TicketListing listing) { return from(listing, null); }
}
