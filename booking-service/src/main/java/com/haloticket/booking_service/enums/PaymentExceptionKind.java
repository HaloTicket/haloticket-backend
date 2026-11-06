package com.haloticket.booking_service.enums;

/** Why a payment needs a person to look at it. Values are stored as text (payment_exceptions.kind, CHECK constraint). */
public enum PaymentExceptionKind {
    LATE_PAYMENT_NO_STOCK,
    LATE_PAYMENT_SEATS_UNAVAILABLE,
    LATE_PAYMENT_UNEXPECTED_STATUS,
    ISSUANCE_FAILED
}
