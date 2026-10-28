package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.StaffRole;

import java.time.LocalDateTime;

public record MemberInvitationPreviewResponse(String eventId, String eventTitle, StaffRole role, LocalDateTime expiresAt, String state) {}
