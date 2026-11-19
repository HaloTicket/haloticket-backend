package com.haloticket.booking_service.enums;

/** Lý do một khoản thanh toán cần người xem xét. Giá trị lưu dạng chữ (cột payment_exceptions.kind, có ràng buộc CHECK). */
public enum PaymentExceptionKind {
    LATE_PAYMENT_NO_STOCK,
    LATE_PAYMENT_SEATS_UNAVAILABLE,
    LATE_PAYMENT_UNEXPECTED_STATUS,
    ISSUANCE_FAILED,
    /** Số tiền đã thanh toán lệch số tiền của đơn: không xác nhận đơn, chờ quản trị viên hoàn tiền hoặc xử lý. */
    AMOUNT_MISMATCH
}
