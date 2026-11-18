package com.haloticket.payment_service.enums;

/** Trạng thái xác minh tài khoản nhận tiền: chưa xác minh, đang chờ, đã xác minh, bị từ chối hoặc bị thu hồi. */
public enum PayoutAccountVerificationStatus {
    UNVERIFIED,
    PENDING,
    VERIFIED,
    REJECTED,
    REVOKED
}
