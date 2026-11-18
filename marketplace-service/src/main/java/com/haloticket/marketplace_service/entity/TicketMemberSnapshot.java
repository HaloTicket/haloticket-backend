package com.haloticket.marketplace_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.math.BigDecimal;

/**
 * Bản chụp thông tin một vé trong tin bán lại hoặc lượt chuyển nhượng (loại vé, chỗ ngồi, giá gốc)
 * và cờ đã bán. Được nhúng vào bảng của entity chứa nó.
 */
@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class TicketMemberSnapshot {
    @Column(name = "ticket_id", nullable = false) private String ticketId;
    @Column(name = "ticket_type_id") private String ticketTypeId;
    @Column(name = "showtime_id") private String showtimeId;
    @Column(name = "ticket_type_name") private String ticketTypeName;
    @Column(name = "section_name") private String section;
    @Column(name = "row_name") private String row;
    @Column(name = "seat_name") private String seat;
    @Column(name = "original_price", precision = 19, scale = 0) private BigDecimal originalPrice;
    @Column(name = "sold", nullable = false) private boolean sold;
}
