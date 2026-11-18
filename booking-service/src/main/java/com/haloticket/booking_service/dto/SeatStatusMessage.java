package com.haloticket.booking_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Thông điệp trạng thái ghế (trống, đang giữ, đã bán) đẩy tới client qua WebSocket. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatStatusMessage {
    private String eventId;
    private String showtimeId;
    private String seatId; // hoặc nhãn ghế như "A-1"
    private String status; // ví dụ "AVAILABLE", "HELD", "SOLD"
}
