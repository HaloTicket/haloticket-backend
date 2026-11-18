package com.haloticket.payment_service.enums;

/** Trạng thái giao dịch cổng thanh toán: thành công, thất bại hoặc chờ xác minh. */
public enum TransactionStatus {
    SUCCESS, FAILED, PENDING_VERIFICATION
}
