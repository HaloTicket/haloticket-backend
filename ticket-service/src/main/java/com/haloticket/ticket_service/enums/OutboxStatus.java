package com.haloticket.ticket_service.enums;

/** Trạng thái của sự kiện outbox: chờ gửi, đã gửi lên Kafka hoặc gửi thất bại. */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
