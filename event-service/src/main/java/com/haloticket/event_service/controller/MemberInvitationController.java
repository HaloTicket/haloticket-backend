package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.MemberInvitationTokenRequest;
import com.haloticket.event_service.dto.response.MemberInvitationAcceptResponse;
import com.haloticket.event_service.dto.response.MemberInvitationPreviewResponse;
import com.haloticket.event_service.service.EventMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/member-invitations")
@RequiredArgsConstructor
public class MemberInvitationController {

    private final EventMemberService eventMemberService;

    @PostMapping("/preview")
    public ResponseEntity<MemberInvitationPreviewResponse> preview(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.previewInvitation(request.getToken(), authorization));
    }

    @PostMapping("/accept")
    public ResponseEntity<MemberInvitationAcceptResponse> accept(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.acceptInvitation(request.getToken(), authorization));
    }

    @PostMapping("/decline")
    public ResponseEntity<Void> decline(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        eventMemberService.declineInvitation(request.getToken(), authorization);
        return ResponseEntity.ok().build();
    }
}
