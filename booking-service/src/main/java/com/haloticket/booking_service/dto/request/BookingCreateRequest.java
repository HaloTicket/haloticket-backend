package com.haloticket.booking_service.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class BookingCreateRequest {
    private String customerId;
    private String eventId;
    private String voucherId;
    private Double totalAmount;
    private List<String> seatIds;
}