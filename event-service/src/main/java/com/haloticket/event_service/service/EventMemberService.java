package com.haloticket.event_service.service;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.enums.StaffRole;

import java.util.List;

public interface EventMemberService {
    List<EventMemberResponse> getEventMembers(String eventId, String authorization);
    EventMemberResponse inviteMember(String eventId, EventMemberInviteRequest request, String authorization);
    EventMemberResponse changeMemberRole(String eventId, String assignmentId, StaffRole newRole, String authorization);
    EventMemberResponse changeMemberStatus(String eventId, String assignmentId, String newStatus, String authorization);
    void removeMember(String eventId, String assignmentId, String authorization);
    void transferOwner(String eventId, String memberId, String authorization);
    StaffRole getMyRole(String eventId, String authorization);
    EventAccess getMyAccess(String eventId, String authorization);
}
