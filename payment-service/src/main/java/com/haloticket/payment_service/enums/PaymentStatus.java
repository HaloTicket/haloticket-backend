package com.haloticket.payment_service.enums;

/** Trạng thái thanh toán: chờ, thành công, thất bại hoặc đã hoàn tiền. */
public enum PaymentStatus {
    PENDING, SUCCESS, FAILED, REFUNDED
}
