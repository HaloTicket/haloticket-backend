package com.haloticket.booking_service.dto.request;

import lombok.Data;

@Data
public class BookingItemRequest {
    private String ticketTypeId;
    private Integer quantity;
    private Double unitPrice;
}
