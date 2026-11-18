package com.haloticket.booking_service.enums;

/** Trạng thái xử lý của ngoại lệ thanh toán. Giá trị lưu dạng chữ (cột payment_exceptions.status, có ràng buộc CHECK). */
public enum PaymentExceptionStatus {
    OPEN,
    RESOLVED,
    REFUNDED_MANUALLY
}
