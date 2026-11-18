package com.haloticket.payment_service.enums;

/** Trạng thái yêu cầu chi trả: chờ duyệt, đã duyệt, đang chuyển, hoàn tất, thất bại, bị từ chối, đã hủy, cần xác minh hoặc tranh chấp. */
public enum PayoutStatus {
    PENDING, APPROVED, PROCESSING, COMPLETED, FAILED, REJECTED, CANCELLED, NEEDS_VERIFICATION, DISPUTE
}
