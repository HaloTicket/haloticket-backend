package com.haloticket.user_service.enums;

/** Trạng thái một lần thử eKYC: từ mới tạo, đang xử lý đến đạt, thất bại, chờ duyệt tay, hết hạn hoặc lỗi. */
public enum EkycAttemptStatus {
    CREATED,
    PROCESSING,
    PASSED,
    FAILED,
    MANUAL_REVIEW,
    EXPIRED,
    ERROR
}
