package com.haloticket.event_service.enums;

/** Vòng đời của sự kiện: nháp, chờ duyệt, đã duyệt, đang mở bán ({@code LIVE}), đã kết thúc và đã hủy. */
public enum EventStatus {
    DRAFT, PENDING, APPROVED, LIVE, COMPLETED, CANCELLED
}
