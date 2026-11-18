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

/** API cho người được mời xem trước, chấp nhận hoặc từ chối lời mời tham gia nhân sự sự kiện bằng token trong liên kết mời. */
@RestController
@RequestMapping("/api/events/member-invitations")
@RequiredArgsConstructor
public class MemberInvitationController {

    private final EventMemberService eventMemberService;

    /**
     * Xem trước nội dung lời mời mà không thay đổi dữ liệu.
     * @param request chứa token lời mời
     * @param authorization header {@code Authorization} của người được mời
     * @return thông tin xem trước lời mời
     */
    @PostMapping("/preview")
    public ResponseEntity<MemberInvitationPreviewResponse> preview(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.previewInvitation(request.getToken(), authorization));
    }

    /**
     * Chấp nhận lời mời bằng token.
     * @param request chứa token lời mời
     * @param authorization header {@code Authorization} của người được mời
     * @return kết quả chấp nhận
     */
    @PostMapping("/accept")
    public ResponseEntity<MemberInvitationAcceptResponse> accept(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.acceptInvitation(request.getToken(), authorization));
    }

    /**
     * Từ chối lời mời bằng token.
     * @param request chứa token lời mời
     * @param authorization header {@code Authorization} của người được mời
     * @return 200 không có thân
     */
    @PostMapping("/decline")
    public ResponseEntity<Void> decline(
            @RequestBody MemberInvitationTokenRequest request, @RequestHeader("Authorization") String authorization) {
        eventMemberService.declineInvitation(request.getToken(), authorization);
        return ResponseEntity.ok().build();
    }
}
