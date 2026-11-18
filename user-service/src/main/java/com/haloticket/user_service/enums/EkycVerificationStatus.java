package com.haloticket.user_service.enums;

/** Trạng thái xác minh eKYC của hồ sơ: chưa bắt đầu, chờ xử lý, đã duyệt, bị từ chối, cần bổ sung, bị thu hồi, hết hạn. */
public enum EkycVerificationStatus {
    NOT_STARTED, PENDING, APPROVED, REJECTED, REQUIRES_UPDATE, REVOKED, EXPIRED
}
