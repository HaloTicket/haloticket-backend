package com.haloticket.marketplace_service.dto;

import com.haloticket.marketplace_service.entity.TicketMemberSnapshot;
import java.math.BigDecimal;

/** Thông tin một vé trong tin bán lại: vị trí chỗ ngồi, giá gốc và giá bán. */
public record ListingTicketResponse(String ticketId, String ticketTypeId, String showtimeId,
                                    String ticketTypeName, String section, String row, String seat,
                                    BigDecimal originalPrice, BigDecimal price) {
    /**
     * Dựng phản hồi từ bản chụp vé của tin bán lại.
     *
     * @param member bản chụp thông tin vé
     * @param price giá bán của tin
     * @return phản hồi tương ứng
     */
    public static ListingTicketResponse from(TicketMemberSnapshot member, BigDecimal price) {
        return new ListingTicketResponse(member.getTicketId(), member.getTicketTypeId(), member.getShowtimeId(),
                member.getTicketTypeName(), member.getSection(), member.getRow(), member.getSeat(), member.getOriginalPrice(), price);
    }
}
