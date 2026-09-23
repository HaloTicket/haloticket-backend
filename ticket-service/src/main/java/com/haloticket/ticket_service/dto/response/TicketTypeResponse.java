package com.haloticket.ticket_service.dto.response;

import com.haloticket.ticket_service.enums.TicketTypeStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TicketTypeResponse {
    private String id;
    private String eventId;
    private String showtimeId;
    private String name;
    private Double price;
    private Boolean isFree;
    private Integer totalQuantity;
    private Integer initialQuantity;
    private Integer minPerOrder;
    private Integer maxPerOrder;
    private String description;
    private TicketTypeStatus status;
}