package com.haloticket.booking_service.dto.response;

import lombok.Data;

/** Loại vé kèm giới hạn số vé tối thiểu và tối đa mỗi đơn. */
@Data
public class TicketTypeDto {
    private String id;
    private String name;
    private Integer minPerOrder;
    private Integer maxPerOrder;
}
