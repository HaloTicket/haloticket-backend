package com.haloticket.event_service.enums;

/** Trạng thái phân công nhân sự: đang hiệu lực, đã thu hồi, lời mời đang chờ ({@code PENDING}) hoặc đã bị từ chối ({@code DECLINED}). */
public enum StaffAssignmentStatus {
    ACTIVE, REVOKED, PENDING, DECLINED
}
