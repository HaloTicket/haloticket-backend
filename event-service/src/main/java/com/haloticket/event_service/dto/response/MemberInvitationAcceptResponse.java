package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.StaffRole;

public record MemberInvitationAcceptResponse(String eventId, StaffRole role) {}
