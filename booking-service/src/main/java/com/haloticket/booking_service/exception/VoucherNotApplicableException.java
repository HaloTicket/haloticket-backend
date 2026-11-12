package com.haloticket.booking_service.exception;

/** The voucher on an order cannot be used; the message is written for the buyer. Answered with 400 VOUCHER_NOT_APPLICABLE. */
public class VoucherNotApplicableException extends IllegalArgumentException {
    public VoucherNotApplicableException(String message) {
        super(message);
    }
}
