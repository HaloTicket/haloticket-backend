package com.haloticket.booking_service.enums;

/** Trạng thái gửi của một bản ghi outbox: chờ gửi, đã gửi, gửi thất bại. */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
