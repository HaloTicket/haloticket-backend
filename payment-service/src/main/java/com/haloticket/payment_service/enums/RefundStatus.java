package com.haloticket.payment_service.enums;

/** Trạng thái hoàn tiền: chờ, đang xử lý, hoàn tất hoặc thất bại. */
public enum RefundStatus {
    PENDING, PROCESSING, COMPLETED, FAILED
}
