package com.haloticket.booking_service.dto.request;

import lombok.Data;

@Data
public class BookingItemRequest {
    private String ticketTypeId;
    private Integer quantity;
    private Double unitPrice;
    
    // true for a general-admission (no seat) item: its quantity is held as stock, not as seats.
    private Boolean generalAdmission;
}
