package com.haloticket.booking_service.enums;

/** Values are stored as text (payment_exceptions.status, CHECK constraint). */
public enum PaymentExceptionStatus {
    OPEN,
    RESOLVED,
    REFUNDED_MANUALLY
}
