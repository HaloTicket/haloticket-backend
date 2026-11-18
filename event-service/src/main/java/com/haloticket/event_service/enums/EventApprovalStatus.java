package com.haloticket.event_service.enums;

/** Trạng thái duyệt sự kiện của quản trị viên: nháp, chờ duyệt, đã duyệt, bị từ chối và chờ duyệt lại sau khi sửa ({@code UPDATE_PENDING}). */
public enum EventApprovalStatus {
    DRAFT, PENDING, APPROVED, REJECTED, UPDATE_PENDING
}
