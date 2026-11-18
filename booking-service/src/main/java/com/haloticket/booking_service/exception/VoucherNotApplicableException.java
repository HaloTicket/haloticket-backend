package com.haloticket.booking_service.exception;

/** Voucher của đơn không dùng được; thông điệp viết cho người mua. Được trả lời bằng HTTP 400 với mã VOUCHER_NOT_APPLICABLE. */
public class VoucherNotApplicableException extends IllegalArgumentException {
    /**
     * Tạo ngoại lệ với lý do dành cho người mua.
     * @param message lý do voucher không áp dụng được
     */
    public VoucherNotApplicableException(String message) {
        super(message);
    }
}
