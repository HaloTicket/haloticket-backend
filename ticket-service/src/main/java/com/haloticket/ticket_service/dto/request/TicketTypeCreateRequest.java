package com.haloticket.ticket_service.dto.request;

import lombok.Data;

@Data
public class TicketTypeCreateRequest {
    private String id;
    private String eventId;
    private String name;
    private Double price;
    private Boolean isFree;
    private Integer totalQuantity;
    private Integer minPerOrder;
    private Integer maxPerOrder;
    private String startSaleDate;
    private String endSaleDate;
    private String description;
}