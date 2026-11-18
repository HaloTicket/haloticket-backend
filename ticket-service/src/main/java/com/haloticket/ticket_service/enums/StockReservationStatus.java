package com.haloticket.ticket_service.enums;

/** Trạng thái giữ chỗ tồn kho: đang giữ, đã dùng để phát hành vé, đã nhả hoặc đã hết hạn. */
public enum StockReservationStatus {
    HELD, CONSUMED, RELEASED, EXPIRED
}
