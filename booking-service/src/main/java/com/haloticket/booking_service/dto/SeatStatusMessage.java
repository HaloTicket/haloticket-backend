package com.haloticket.booking_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatStatusMessage {
    private String eventId;
    private String showtimeId;
    private String seatId; // or seat label "A-1"
    private String status; // e.g. "AVAILABLE", "HELD", "SOLD"
}
