package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.StaffRole;

import java.time.LocalDateTime;

/** Thông tin xem trước lời mời: sự kiện, vai trò, hạn dùng và trạng thái hiện tại của lời mời. */
public record MemberInvitationPreviewResponse(String eventId, String eventTitle, StaffRole role, LocalDateTime expiresAt, String state) {}
