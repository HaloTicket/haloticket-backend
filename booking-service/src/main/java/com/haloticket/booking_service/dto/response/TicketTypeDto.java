package com.haloticket.booking_service.dto.response;

import lombok.Data;

@Data
public class TicketTypeDto {
    private String id;
    private String name;
    private Integer minPerOrder;
    private Integer maxPerOrder;
}
