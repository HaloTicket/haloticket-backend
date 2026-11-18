package com.haloticket.user_service.enums;

/** Vòng đời của thử thách OTP: chờ nhập, đã xác minh, đã dùng, hết hạn hoặc bị khóa. */
public enum OtpStatus {
    PENDING,
    VERIFIED,
    CONSUMED,
    EXPIRED,
    LOCKED
}
