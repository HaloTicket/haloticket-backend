package com.haloticket.payment_service.enums;

/** Trạng thái bản ghi outbox: chờ gửi, đã xử lý hoặc thất bại. */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
