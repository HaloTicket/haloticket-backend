package com.haloticket.event_service.service;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.dto.response.MemberInvitationAcceptResponse;
import com.haloticket.event_service.dto.response.MemberInvitationPreviewResponse;
import com.haloticket.event_service.enums.StaffRole;

import java.util.List;

/** Nghiệp vụ quản lý thành viên (nhân sự) của sự kiện: mời, gửi lại lời mời, chấp nhận hoặc từ chối lời mời, đổi vai trò và trạng thái, xóa, chuyển quyền chủ sự kiện và tra cứu quyền của người gọi. */
public interface EventMemberService {
    /** Lấy danh sách thành viên và lời mời của sự kiện, bổ sung tên, email, ảnh đại diện từ user-service. Chỉ OWNER, ADMIN, MANAGER được xem. */
    List<EventMemberResponse> getEventMembers(String eventId, String authorization);
    /** Mời một người vào sự kiện qua email với vai trò cho trước, sinh token lời mời dùng một lần và phát thông báo gửi email. */
    EventMemberResponse inviteMember(String eventId, EventMemberInviteRequest request, String authorization);
    /** Gửi lại lời mời đang chờ hoặc đã bị từ chối với token mới, chịu giới hạn tần suất và số lần gửi lại. */
    EventMemberResponse resendInvitation(String eventId, String assignmentId, String authorization);
    /** Xem trước nội dung lời mời (sự kiện, vai trò, hạn dùng) bằng token, chỉ dành cho đúng người được mời. */
    MemberInvitationPreviewResponse previewInvitation(String token, String authorization);
    /** Chấp nhận lời mời bằng token: thành viên chuyển sang {@code ACTIVE} và token bị hủy. */
    MemberInvitationAcceptResponse acceptInvitation(String token, String authorization);
    /** Từ chối lời mời bằng token: lời mời chuyển sang {@code DECLINED} và token bị hủy. */
    void declineInvitation(String token, String authorization);
    /** Đổi vai trò của một thành viên; chỉ OWNER được đặt hoặc sửa vai trò OWNER. */
    EventMemberResponse changeMemberRole(String eventId, String assignmentId, StaffRole newRole, String authorization);
    /** Khóa hoặc mở lại một thành viên đang hoạt động (chuyển giữa {@code ACTIVE} và {@code REVOKED}). */
    EventMemberResponse changeMemberStatus(String eventId, String assignmentId, String newStatus, String authorization);
    /** Xóa thành viên hoặc hủy lời mời khỏi sự kiện. */
    void removeMember(String eventId, String assignmentId, String authorization);
    /** Chuyển quyền chủ sự kiện cho một thành viên đang hoạt động; chủ cũ bị hạ xuống ADMIN. Chỉ OWNER được gọi. */
    void transferOwner(String eventId, String memberId, String authorization);
    /** Lấy vai trò của người gọi trong sự kiện; ném 403 {@code NOT_IN_EVENT} nếu họ không thuộc sự kiện. */
    StaffRole getMyRole(String eventId, String authorization);
    /** Lấy vai trò cùng tập quyền của người gọi trên sự kiện. */
    EventAccess getMyAccess(String eventId, String authorization);
}
