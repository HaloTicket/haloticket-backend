package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.service.EventMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** API quản lý thành viên (nhân sự) của một sự kiện: mời, gửi lại lời mời, đổi vai trò hoặc trạng thái, gỡ, chuyển quyền chủ sở hữu và xem quyền của chính mình. */
@RestController
@RequestMapping("/api/events/{eventId}/members")
@RequiredArgsConstructor
public class EventMemberController {

    private final EventMemberService eventMemberService;

    /**
     * Lấy danh sách thành viên của sự kiện.
     * @param eventId mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return danh sách thành viên
     */
    @GetMapping
    public ResponseEntity<List<EventMemberResponse>> getMembers(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.getEventMembers(eventId, authorization));
    }

    /**
     * Mời một người vào nhân sự của sự kiện.
     * @param eventId mã sự kiện
     * @param request thông tin người được mời và vai trò
     * @param authorization header {@code Authorization} của người gọi
     * @return thành viên vừa được mời
     */
    @PostMapping
    public ResponseEntity<EventMemberResponse> inviteMember(
            @PathVariable String eventId,
            @RequestBody EventMemberInviteRequest request,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.inviteMember(eventId, request, authorization));
    }

    /**
     * Gửi lại lời mời đang chờ.
     * @param eventId mã sự kiện
     * @param assignmentId mã phân công nhân sự
     * @param authorization header {@code Authorization} của người gọi
     * @return thành viên sau khi gửi lại
     */
    @PostMapping("/{assignmentId}/resend")
    public ResponseEntity<EventMemberResponse> resendInvitation(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.resendInvitation(eventId, assignmentId, authorization));
    }

    /**
     * Đổi vai trò của một thành viên.
     * @param eventId mã sự kiện
     * @param assignmentId mã phân công nhân sự
     * @param newRole vai trò mới
     * @param authorization header {@code Authorization} của người gọi
     * @return thành viên sau khi đổi vai trò
     */
    @PutMapping("/{assignmentId}/role")
    public ResponseEntity<EventMemberResponse> changeMemberRole(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestParam StaffRole newRole,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.changeMemberRole(eventId, assignmentId, newRole, authorization));
    }

    /**
     * Đổi trạng thái của một thành viên.
     * @param eventId mã sự kiện
     * @param assignmentId mã phân công nhân sự
     * @param newStatus trạng thái mới
     * @param authorization header {@code Authorization} của người gọi
     * @return thành viên sau khi đổi trạng thái
     */
    @PutMapping("/{assignmentId}/status")
    public ResponseEntity<EventMemberResponse> changeMemberStatus(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestParam String newStatus,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.changeMemberStatus(eventId, assignmentId, newStatus, authorization));
    }

    /**
     * Gỡ một thành viên khỏi sự kiện.
     * @param eventId mã sự kiện
     * @param assignmentId mã phân công nhân sự
     * @param authorization header {@code Authorization} của người gọi
     * @return 204 không có thân
     */
    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization
    ) {
        eventMemberService.removeMember(eventId, assignmentId, authorization);
        return ResponseEntity.noContent().build();
    }

    /**
     * Chuyển quyền chủ sở hữu sự kiện cho một thành viên khác.
     * @param eventId mã sự kiện
     * @param assignmentId mã phân công của người nhận quyền
     * @param authorization header {@code Authorization} của người gọi
     * @return 204 không có thân
     */
    @PostMapping("/{assignmentId}/transfer-owner")
    public ResponseEntity<Void> transferOwner(
            @PathVariable String eventId,
            @PathVariable String assignmentId,
            @RequestHeader("Authorization") String authorization
    ) {
        eventMemberService.transferOwner(eventId, assignmentId, authorization);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lấy quyền truy cập của người gọi trên sự kiện.
     * @param eventId mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return vai trò và các quyền của người gọi
     */
    @GetMapping("/my-access")
    public ResponseEntity<com.haloticket.event_service.dto.response.EventAccessResponse> getMyAccess(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(com.haloticket.event_service.dto.response.EventAccessResponse.from(
                eventMemberService.getMyAccess(eventId, authorization)));
    }

    /**
     * Lấy vai trò của người gọi trong sự kiện.
     * @param eventId mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return vai trò nhân sự
     */
    @GetMapping("/my-role")
    public ResponseEntity<StaffRole> getMyRole(
            @PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventMemberService.getMyRole(eventId, authorization));
    }
}
