package com.haloticket.marketplace_service.dto;

import com.haloticket.marketplace_service.entity.TicketMemberSnapshot;
import java.math.BigDecimal;

public record ListingTicketResponse(String ticketId, String ticketTypeId, String showtimeId,
                                    String ticketTypeName, String section, String row, String seat,
                                    BigDecimal originalPrice, BigDecimal price) {
    public static ListingTicketResponse from(TicketMemberSnapshot member, BigDecimal price) {
        return new ListingTicketResponse(member.getTicketId(), member.getTicketTypeId(), member.getShowtimeId(),
                member.getTicketTypeName(), member.getSection(), member.getRow(), member.getSeat(), member.getOriginalPrice(), price);
    }
}
