package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.StaffRole;

/** Kết quả chấp nhận lời mời: sự kiện và vai trò mà người dùng vừa nhận. */
public record MemberInvitationAcceptResponse(String eventId, StaffRole role) {}
