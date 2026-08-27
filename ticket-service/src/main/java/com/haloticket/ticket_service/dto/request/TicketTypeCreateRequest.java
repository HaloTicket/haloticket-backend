package com.haloticket.ticket_service.dto.request;

import lombok.Data;

@Data
public class TicketTypeCreateRequest {
    private String eventId;
    private String name;
    private Double price;
    private Integer totalQuantity;
}