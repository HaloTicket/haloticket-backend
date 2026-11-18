package com.haloticket.booking_service.enums;

/** Vòng đời của đơn đặt vé: chờ thanh toán, đã thanh toán, đã xác nhận (phát vé), hủy, hết hạn giữ chỗ, hoàn tiền. */
public enum BookingStatus {
    PENDING, PAID, CONFIRMED, CANCELLED, EXPIRED, REFUNDED
}
