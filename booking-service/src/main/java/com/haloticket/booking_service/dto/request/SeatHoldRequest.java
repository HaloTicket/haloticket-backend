package com.haloticket.booking_service.dto.request;

import lombok.Data;

@Data
public class SeatHoldRequest {
    private String eventId;
    private String showtimeId;
    private String seatId; // e.g. "A-1" or actual DB ID
    private String userId; // Or anonymous session ID
}
