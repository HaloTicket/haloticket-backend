package com.haloticket.booking_service.dto.request;

import lombok.Data;

/** Yêu cầu giữ hoặc nhả một ghế của suất chiếu. */
@Data
public class SeatHoldRequest {
    private String eventId;
    private String showtimeId;
    private String seatId; // ví dụ "A-1" hoặc id thực trong CSDL
    private String userId; // hoặc id phiên ẩn danh
}
