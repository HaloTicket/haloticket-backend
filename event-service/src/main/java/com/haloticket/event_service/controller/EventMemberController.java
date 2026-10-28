package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.service.EventMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events/{eventId}/members")
@RequiredArgsConstructor
public class EventMemberController {

    private final EventMemberService eventMemberService;

    @GetMapping
    public ResponseEntity<List<EventMemberResponse>> getMembers(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.getEventMembers(eventId, authorization));
    }

    @PostMapping
    public ResponseEntity<EventMemberResponse> inviteMember(
            @PathVariable String eventId,
            @RequestBody EventMemberInviteRequest request,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.inviteMember(eventId, request, authorization));
    }

    @PostMapping("/{assignmentId}/resend")
    public ResponseEntity<EventMemberResponse> resendInvitation(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.resendInvitation(eventId, assignmentId, authorization));
    }

    @PutMapping("/{assignmentId}/role")
    public ResponseEntity<EventMemberResponse> changeMemberRole(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestParam StaffRole newRole,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.changeMemberRole(eventId, assignmentId, newRole, authorization));
    }

    @PutMapping("/{assignmentId}/status")
    public ResponseEntity<EventMemberResponse> changeMemberStatus(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestParam String newStatus,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.changeMemberStatus(eventId, assignmentId, newStatus, authorization));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization
    ) {
        eventMemberService.removeMember(eventId, assignmentId, authorization);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{assignmentId}/transfer-owner")
    public ResponseEntity<Void> transferOwner(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization
    ) {
        eventMemberService.transferOwner(eventId, assignmentId, authorization);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my-access")
    public ResponseEntity<com.haloticket.event_service.dto.response.EventAccessResponse> getMyAccess(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(com.haloticket.event_service.dto.response.EventAccessResponse.from(
                eventMemberService.getMyAccess(eventId, authorization)));
    }

    @GetMapping("/my-role")
    public ResponseEntity<StaffRole> getMyRole(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.getMyRole(eventId, authorization));
    }
}
