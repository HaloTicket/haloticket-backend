package com.haloticket.ticket_service.dto.response;

import com.haloticket.ticket_service.enums.TicketTypeStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TicketTypeResponse {
    private String id;
    private String eventId;
    private String name;
    private Double price;
    private Integer totalQuantity;
    private TicketTypeStatus status;
}