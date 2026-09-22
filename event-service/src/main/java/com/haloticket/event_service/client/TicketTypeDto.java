package com.haloticket.event_service.client;

import lombok.Data;

@Data
public class TicketTypeDto {
    private String id;
    private String name;
    private Double price;
    private Integer totalQuantity;
}
