package com.haloticket.marketplace_service.enums;
/** Trạng thái bản ghi outbox: chờ phát, đã phát thành công hoặc phát thất bại. */
public enum OutboxStatus { PENDING, PROCESSED, FAILED }
