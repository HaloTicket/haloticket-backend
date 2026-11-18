package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.service.EventAccessService;
import com.haloticket.event_service.client.UserInternalServiceClient;
import com.haloticket.event_service.client.UserInviteResponse;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.config.MemberInvitationProperties;
import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.MemberInvitationAcceptResponse;
import com.haloticket.event_service.dto.response.MemberInvitationPreviewResponse;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.dto.response.InternalUserResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/** Cài đặt nghiệp vụ thành viên của sự kiện trên bảng {@code StaffAssignment}. Gọi user-service để tìm hoặc tạo tài khoản người được mời, và phát thông báo email lời mời qua Kafka. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventMemberServiceImpl implements EventMemberService {

    private final StaffAssignmentRepository staffAssignmentRepository;
    private final EventRepository eventRepository;
    private final UserServiceClient userServiceClient;
    private final UserInternalServiceClient userInternalServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EventPermissions permissions;
    private final CallerIds callers;
    private final MemberInvitationProperties invitations;

    /** Id người gọi trong user-service; token sai trả 401, user-service ngừng hoạt động trả 503. */
    private String getUserIdFromAuth(String authorization) {
        return callers.userId(authorization);
    }

    /** Tính vai trò và quyền của một người dùng trên sự kiện (admin nền tảng được tính riêng). */
    private EventAccess accessOf(String eventId, String userId) {
        return permissions.forUser(userId, eventId, EventAccessService.isPlatformAdmin());
    }

    /**
     * Bắt buộc người dùng có một trong các vai trò cho phép.
     * @param eventId id sự kiện
     * @param userId id người dùng
     * @param allowedRoles các vai trò được chấp nhận
     * @return vai trò thực tế của người dùng
     * @throws ResponseStatusException 403 {@code ACCOUNT_INACTIVE} khi tài khoản thành viên bị khóa, 403 {@code FORBIDDEN_ROLE} khi vai trò không nằm trong danh sách
     */
    private StaffRole requireRole(String eventId, String userId, StaffRole... allowedRoles) {
        EventAccess access = accessOf(eventId, userId);
        if (access.inactive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE");
        }
        for (StaffRole role : allowedRoles) {
            if (access.role() == role) {
                return role;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /** Id phân công trên đường dẫn phải thuộc sự kiện trên đường dẫn (nếu không trả 404 như thể nó không tồn tại). */
    private void requireAssignmentOfEvent(StaffAssignment assignment, String eventId) {
        if (assignment.getEvent() == null || !eventId.equals(assignment.getEvent().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
        }
    }

    /** Liệt kê thành viên và lời mời của sự kiện. Với mỗi người đã có tài khoản, thử lấy tên, email, ảnh từ user-service; lỗi gọi dịch vụ thì dùng dữ liệu đã lưu. Chỉ OWNER, ADMIN, MANAGER được xem. */
    @Override
    public List<EventMemberResponse> getEventMembers(String eventId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        // cho phép OWNER, ADMIN, MANAGER xem danh sách thành viên
        requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN, StaffRole.MANAGER);

        List<StaffAssignment> assignments = staffAssignmentRepository.findByEventId(eventId);
        List<EventMemberResponse> responses = assignments.stream().map(a -> {
            String avatarUrl = null;
            String name = a.getName();
            String email = a.getEmail();
            if (a.getUserId() != null) {
                try {
                    InternalUserResponse userResp = userInternalServiceClient.getUserById(a.getUserId());
                    if (userResp != null) {
                        if (userResp.getAvatarUrl() != null) avatarUrl = userResp.getAvatarUrl();
                        if (userResp.getFullName() != null) name = userResp.getFullName();
                        if (userResp.getEmail() != null) email = userResp.getEmail();
                    }
                } catch (Exception ex) {
                    // bỏ qua, dùng lại thông tin đã lưu trong phân công
                }
            }
            return EventMemberResponse.builder()
                .id(a.getId())
                .name(name)
                .email(email)
                .avatar(avatarUrl)
                .role(a.getRole())
                .status(a.statusLabel())
                .addedAt(a.getAssignedAt())
                .invitationExpiresAt(a.getStatus() == StaffAssignmentStatus.PENDING ? a.getInvitationExpiresAt() : null)
                .build();
        }).collect(Collectors.toCollection(java.util.ArrayList::new));

        return responses;
    }

    /**
     * Mời một người vào sự kiện qua email. Chỉ OWNER hoặc ADMIN được mời; ADMIN không được gán OWNER và không ai được mời làm OWNER. Từ chối mời chính mình, người đã là thành viên hoặc còn lời mời đang chờ, và vượt giới hạn số lời mời mỗi ngày. Sinh token dùng một lần (chỉ lưu mã băm), lưu phân công ở trạng thái {@code PENDING} rồi phát thông báo gửi email.
     * @param eventId id sự kiện
     * @param request email và vai trò được mời
     * @param authorization header Authorization của người mời
     * @return thông tin thành viên vừa được mời
     * @throws ResponseStatusException 400 khi email hoặc vai trò sai, 403 khi thiếu quyền, 404 khi không có sự kiện, 409 {@code ALREADY_MEMBER} hoặc {@code INVITATION_PENDING}, 422 {@code CANNOT_INVITE_OWNER}
     * @throws RateLimitedException khi vượt giới hạn lời mời trong 24 giờ
     */
    @Override
    public EventMemberResponse inviteMember(String eventId, EventMemberInviteRequest request, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        if (request == null || request.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        if (currentRole == StaffRole.ADMIN && request.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_CANNOT_ASSIGN_OWNER");
        }
        // lời mời không bao giờ tạo ra chủ sự kiện: quyền chủ chỉ đổi qua transfer-owner
        if (request.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(422), "CANNOT_INVITE_OWNER");
        }
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isEmpty() || !email.contains("@") || email.length() > 254) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        // người gọi (thành viên ACTIVE của sự kiện) không được mời chính địa chỉ email của mình
        staffAssignmentRepository.findByEventIdAndUserId(eventId, uid)
                .filter(self -> self.getStatus() == StaffAssignmentStatus.ACTIVE)
                .ifPresent(self -> {
                    String callerEmail = self.getEmail();
                    if (callerEmail == null || callerEmail.isBlank()) {
                        try {
                            InternalUserResponse callerUser = userInternalServiceClient.getUserById(uid);
                            callerEmail = callerUser == null ? null : callerUser.getEmail();
                        } catch (Exception ignored) {
                            // không biết email của người gọi nên không có gì để so sánh
                        }
                    }
                    if (callerEmail != null && callerEmail.equalsIgnoreCase(email)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_INVITE_YOURSELF");
                    }
                });

        StaffAssignment existing = pick(staffAssignmentRepository.findAllByEventIdAndEmail(eventId, email));
        requireInvitable(existing);
        requireRoomForAnotherSend(eventId);

        // gọi User Service để tìm tài khoản của người được mời, hoặc tạo mới (chưa có mật khẩu dùng được)
        EventMemberInviteRequest lookup = new EventMemberInviteRequest();
        lookup.setEmail(email);
        lookup.setRole(request.getRole());
        UserInviteResponse inviteRes = userInternalServiceClient.inviteUser(lookup);
        if (existing == null) {
            // dòng được ghi không có email (bù dữ liệu cho chủ sự kiện) thì tìm theo tài khoản
            existing = pick(staffAssignmentRepository.findAllByEventIdAndUserId(eventId, inviteRes.getUserId()));
            requireInvitable(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        StaffAssignment assignment = existing != null ? existing : StaffAssignment.builder().event(event).build();
        assignment.setUserId(inviteRes.getUserId());
        assignment.setEmail(inviteRes.getEmail() != null ? inviteRes.getEmail() : email);
        assignment.setName(inviteRes.getName());
        assignment.setRole(request.getRole());
        assignment.setInvitedBy(uid);
        assignment.setInvitedAt(now);
        assignment.setAssignedAt(now);
        assignment.setRevokedAt(null);
        String token = issueToken(assignment, now);
        assignment.setInvitationResendCount(0);
        try {
            assignment = staffAssignmentRepository.save(assignment);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // nhờ chỉ mục duy nhất (sự kiện, người), lời mời song song cho cùng một người sẽ thua ở đây
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_PENDING");
        }

        publishInvitation(event, assignment, inviteRes.isNewUser(), token);
        return toResponse(assignment, inviteRes.getAvatar());
    }

    /** Mỗi người chỉ ứng với một dòng: nếu dữ liệu cũ có nhiều dòng thì ưu tiên thành viên đang hoạt động, rồi đến lời mời đang chờ. */
    private static StaffAssignment pick(List<StaffAssignment> rows) {
        if (rows == null || rows.isEmpty()) return null;
        return rows.stream().filter(r -> r.getStatus() == StaffAssignmentStatus.ACTIVE).findFirst()
                .or(() -> rows.stream().filter(r -> r.getStatus() == StaffAssignmentStatus.PENDING).findFirst())
                .orElse(rows.get(0));
    }

    /** Giới hạn số lời mời và lần gửi lại của mỗi sự kiện trong 24 giờ, đếm trực tiếp từ các dòng phân công. */
    private void requireRoomForAnotherSend(String eventId) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        if (staffAssignmentRepository.invitationsSentSince(eventId, since) >= invitations.getMaxPerEventPerDay()) {
            LocalDateTime first = staffAssignmentRepository.firstInvitationSince(eventId, since);
            long wait = first == null ? 3600 : java.time.Duration.between(LocalDateTime.now(), first.plusHours(24)).getSeconds();
            throw new RateLimitedException(wait);
        }
    }

    /** Người đã là thành viên, hoặc còn lời mời đang chờ, thì không được mời lại. */
    private void requireInvitable(StaffAssignment existing) {
        if (existing == null) return;
        StaffAssignmentStatus status = existing.getStatus();
        if (status == StaffAssignmentStatus.PENDING && "PENDING".equals(existing.statusLabel())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_PENDING");
        }
        if (status == StaffAssignmentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_MEMBER");
        }
        // lời mời hết hạn hoặc bị từ chối, hay thành viên đã bị khóa, được mời lại trên cùng một dòng
    }

    /**
     * Sinh token lời mời mới, đặt phân công về {@code PENDING} và lưu mã băm cùng hạn dùng.
     * @param assignment phân công được mời
     * @param now thời điểm gửi
     * @return token gốc, chỉ để gửi cho người được mời
     */
    private String issueToken(StaffAssignment assignment, LocalDateTime now) {
        String token = InvitationTokens.newToken();
        assignment.setStatus(StaffAssignmentStatus.PENDING);
        assignment.setInvitationTokenHash(InvitationTokens.hash(token));
        assignment.setInvitationExpiresAt(now.plusHours(invitations.getTtlHours()));
        return token;
    }

    /** Xóa mã băm và hạn dùng của token để lời mời không dùng lại được. */
    private void clearToken(StaffAssignment assignment) {
        assignment.setInvitationTokenHash(null);
        assignment.setInvitationExpiresAt(null);
    }

    /** Phát thông báo lời mời lên Kafka (topic {@code notification.event.member.invitation}) để notification-service gửi email. Lỗi gửi chỉ ghi log, không làm hỏng việc mời. */
    private void publishInvitation(Event event, StaffAssignment assignment, boolean isNewUser, String token) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getId());
        payload.put("eventTitle", event.getTitle());
        payload.put("email", assignment.getEmail());
        payload.put("name", assignment.getName());
        payload.put("role", assignment.getRole().name());
        payload.put("isNewUser", isNewUser);
        payload.put("invitationToken", token);
        payload.put("invitationExpiresAt", String.valueOf(assignment.getInvitationExpiresAt()));
        try {
            kafkaTemplate.send("notification.event.member.invitation", payload);
        } catch (Exception e) {
            log.warn("Failed to send the member invitation notification ({})", e.getClass().getSimpleName());
        }
    }

    /** Chuyển phân công thành response; hạn lời mời chỉ có khi còn {@code PENDING}. */
    private EventMemberResponse toResponse(StaffAssignment assignment, String avatar) {
        return EventMemberResponse.builder()
                .id(assignment.getId())
                .name(assignment.getName())
                .email(assignment.getEmail())
                .avatar(avatar)
                .role(assignment.getRole())
                .status(assignment.statusLabel())
                .addedAt(assignment.getAssignedAt())
                .invitationExpiresAt(assignment.getStatus() == StaffAssignmentStatus.PENDING ? assignment.getInvitationExpiresAt() : null)
                .build();
    }

    /**
     * Gửi lại lời mời đang chờ hoặc đã bị từ chối với token mới. Giữ khoảng cách tối thiểu giữa hai lần gửi, giới hạn số lần gửi lại mỗi lời mời và số lời mời của sự kiện trong ngày.
     * @param eventId id sự kiện
     * @param assignmentId id phân công cần gửi lại
     * @param authorization header Authorization của người gọi (OWNER hoặc ADMIN)
     * @return thông tin thành viên sau khi gửi lại
     * @throws ResponseStatusException 404 {@code MEMBER_NOT_FOUND}, 409 {@code INVITATION_NOT_RESENDABLE} khi lời mời không ở trạng thái gửi lại được
     * @throws RateLimitedException khi gửi quá sớm hoặc hết lượt gửi lại
     */
    @Override
    public EventMemberResponse resendInvitation(String eventId, String assignmentId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);
        StaffAssignment assignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        if (assignment.getEvent() == null || !eventId.equals(assignment.getEvent().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
        }
        boolean declined = assignment.getStatus() == StaffAssignmentStatus.DECLINED;
        if (assignment.getStatus() != StaffAssignmentStatus.PENDING && !declined) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_NOT_RESENDABLE");
        }
        LocalDateTime now = LocalDateTime.now();
        // lời mời đã bị từ chối khi gửi lại thì bắt đầu đếm số lần gửi từ đầu
        int sent = declined || assignment.getInvitationResendCount() == null ? 0 : assignment.getInvitationResendCount();
        if (assignment.getInvitedAt() != null) {
            LocalDateTime next = assignment.getInvitedAt().plusSeconds(invitations.getResendMinSeconds());
            if (next.isAfter(now)) throw new RateLimitedException(java.time.Duration.between(now, next).getSeconds() + 1);
        }
        if (sent >= invitations.getMaxResends()) {
            // hết lượt gửi lại cho lời mời này: chỉ mời lại được sau khi nó hết hạn
            LocalDateTime until = assignment.getInvitationExpiresAt();
            throw new RateLimitedException(until != null && until.isAfter(now) ? java.time.Duration.between(now, until).getSeconds() + 1 : 3600);
        }
        requireRoomForAnotherSend(eventId);
        String token = issueToken(assignment, now);
        assignment.setInvitationResendCount(declined ? 0 : sent + 1);
        assignment.setInvitedBy(uid);
        assignment.setInvitedAt(now);
        assignment = staffAssignmentRepository.save(assignment);
        publishInvitation(assignment.getEvent(), assignment, false, token);
        return toResponse(assignment, null);
    }

    /** Lời mời mà người đang đăng nhập được phép trả lời; mọi trường hợp khác đều trả cùng một lỗi 404 để không lộ thông tin gì. */
    private StaffAssignment openInvitation(String token, String authorization) {
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        String uid = getUserIdFromAuth(authorization);
        if (token == null || token.isBlank() || token.length() > 200) throw notFound;
        StaffAssignment assignment = staffAssignmentRepository.findByInvitationTokenHash(InvitationTokens.hash(token.trim()))
                .orElseThrow(() -> notFound);
        if (assignment.getStatus() != StaffAssignmentStatus.PENDING || !"PENDING".equals(assignment.statusLabel())
                || assignment.getUserId() == null || !assignment.getUserId().equals(uid)) {
            throw notFound;
        }
        return assignment;
    }

    /**
     * Trả thông tin lời mời (sự kiện, vai trò, hạn dùng) cho đúng người được mời mà không thay đổi dữ liệu.
     * @param token token lời mời
     * @param authorization header Authorization của người đang đăng nhập
     * @throws ResponseStatusException 404 {@code INVITATION_NOT_FOUND} khi token sai, hết hạn hoặc không dành cho người này
     */
    @Override
    @Transactional(readOnly = true)
    public MemberInvitationPreviewResponse previewInvitation(String token, String authorization) {
        StaffAssignment assignment = openInvitation(token, authorization);
        return new MemberInvitationPreviewResponse(assignment.getEvent().getId(), assignment.getEvent().getTitle(),
                assignment.getRole(), assignment.getInvitationExpiresAt(), "PENDING");
    }

    /**
     * Chấp nhận lời mời: phân công chuyển sang {@code ACTIVE}, ghi lại thời điểm tham gia và hủy token.
     * @param token token lời mời
     * @param authorization header Authorization của người được mời
     * @return sự kiện và vai trò vừa tham gia
     * @throws ResponseStatusException 404 {@code INVITATION_NOT_FOUND} khi lời mời không hợp lệ
     */
    @Override
    @Transactional
    public MemberInvitationAcceptResponse acceptInvitation(String token, String authorization) {
        StaffAssignment assignment = openInvitation(token, authorization);
        assignment.setStatus(StaffAssignmentStatus.ACTIVE);
        assignment.setAssignedAt(LocalDateTime.now());
        clearToken(assignment);
        staffAssignmentRepository.save(assignment);
        return new MemberInvitationAcceptResponse(assignment.getEvent().getId(), assignment.getRole());
    }

    /**
     * Từ chối lời mời: phân công chuyển sang {@code DECLINED} và token bị hủy.
     * @param token token lời mời
     * @param authorization header Authorization của người được mời
     * @throws ResponseStatusException 404 {@code INVITATION_NOT_FOUND} khi lời mời không hợp lệ
     */
    @Override
    @Transactional
    public void declineInvitation(String token, String authorization) {
        StaffAssignment assignment = openInvitation(token, authorization);
        assignment.setStatus(StaffAssignmentStatus.DECLINED);
        clearToken(assignment);
        staffAssignmentRepository.save(assignment);
    }

    /**
     * Đổi vai trò của thành viên. Chỉ OWNER hoặc ADMIN được gọi; chỉ OWNER được sửa vai trò của chủ sự kiện hoặc gán OWNER, và người được gán OWNER phải đang {@code ACTIVE}.
     * @param eventId id sự kiện
     * @param assignmentId id phân công cần đổi
     * @param newRole vai trò mới
     * @param authorization header Authorization của người gọi
     * @return thành viên sau khi đổi vai trò
     * @throws ResponseStatusException 403 {@code CANNOT_MODIFY_OWNER} hoặc {@code CANNOT_ASSIGN_OWNER}, 404 {@code MEMBER_NOT_FOUND}, 400 {@code MEMBER_NOT_ACTIVE}
     */
    @Override
    public EventMemberResponse changeMemberRole(String eventId, String assignmentId, StaffRole newRole, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        StaffAssignment assignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        requireAssignmentOfEvent(assignment, eventId);
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }
        if (newRole == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_ASSIGN_OWNER");
        }
        if (newRole == StaffRole.OWNER && assignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_ACTIVE");
        }

        assignment.setRole(newRole);
        assignment = staffAssignmentRepository.save(assignment);

        return EventMemberResponse.builder()
                .id(assignment.getId())
                .name(assignment.getName())
                .email(assignment.getEmail())
                .role(assignment.getRole())
                .status(assignment.getStatus().name())
                .addedAt(assignment.getAssignedAt())
                .build();
    }

    /**
     * Khóa ({@code REVOKED}) hoặc mở lại ({@code ACTIVE}) một thành viên. Lời mời chưa trả lời không đổi được ở đây (người nhận tự trả lời, hoặc hủy bằng cách xóa). Chủ sự kiện chỉ bị sửa bởi chính OWNER.
     * @param eventId id sự kiện
     * @param assignmentId id phân công cần đổi
     * @param newStatus trạng thái mới, không phân biệt hoa thường
     * @param authorization header Authorization của người gọi
     * @return thành viên sau khi đổi trạng thái
     * @throws ResponseStatusException 400 {@code INVALID_STATUS} hoặc {@code INVALID_STATUS_TRANSITION}, 403 {@code CANNOT_MODIFY_OWNER}, 404 {@code MEMBER_NOT_FOUND}
     */
    @Override
    public EventMemberResponse changeMemberStatus(String eventId, String assignmentId, String newStatus, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        StaffAssignment assignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        requireAssignmentOfEvent(assignment, eventId);
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }

        StaffAssignmentStatus parsedStatus;
        try {
            parsedStatus = StaffAssignmentStatus.valueOf(newStatus == null ? "" : newStatus.trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
        // chỉ thành viên mới khóa và mở lại được; lời mời do người nhận trả lời (hoặc hủy bằng cách xóa)
        boolean toggle = parsedStatus == StaffAssignmentStatus.ACTIVE || parsedStatus == StaffAssignmentStatus.REVOKED;
        boolean isMember = assignment.getStatus() == StaffAssignmentStatus.ACTIVE || assignment.getStatus() == StaffAssignmentStatus.REVOKED;
        if (!toggle || !isMember) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, toggle ? "INVALID_STATUS_TRANSITION" : "INVALID_STATUS");
        }
        assignment.setStatus(parsedStatus);
        assignment.setRevokedAt(parsedStatus == StaffAssignmentStatus.REVOKED ? LocalDateTime.now() : null);
        assignment = staffAssignmentRepository.save(assignment);

        return EventMemberResponse.builder()
                .id(assignment.getId())
                .name(assignment.getName())
                .email(assignment.getEmail())
                .role(assignment.getRole())
                .status(assignment.getStatus().name())
                .addedAt(assignment.getAssignedAt())
                .build();
    }

    /**
     * Xóa thành viên hoặc hủy lời mời khỏi sự kiện. Chỉ OWNER mới xóa được chủ sự kiện.
     * @param eventId id sự kiện
     * @param assignmentId id phân công cần xóa
     * @param authorization header Authorization của người gọi
     * @throws ResponseStatusException 403 {@code CANNOT_MODIFY_OWNER}, 404 {@code MEMBER_NOT_FOUND}
     */
    @Override
    public void removeMember(String eventId, String assignmentId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        StaffAssignment assignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        requireAssignmentOfEvent(assignment, eventId);
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }

        staffAssignmentRepository.delete(assignment);
    }

    /**
     * Chuyển quyền chủ sự kiện cho một thành viên đang hoạt động; chủ cũ được hạ xuống ADMIN. Chỉ OWNER hiện tại được gọi.
     * @param eventId id sự kiện
     * @param assignmentId id phân công của người nhận quyền
     * @param authorization header Authorization của chủ sự kiện
     * @throws ResponseStatusException 400 {@code INVALID_ASSIGNMENT}, {@code ALREADY_OWNER} hoặc {@code MEMBER_NOT_ACTIVE}, 404 {@code ASSIGNMENT_NOT_FOUND}
     */
    @Override
    public void transferOwner(String eventId, String assignmentId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        requireRole(eventId, uid, StaffRole.OWNER);

        StaffAssignment targetAssignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND"));

        if (!targetAssignment.getEvent().getId().equals(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ASSIGNMENT");
        }

        if (targetAssignment.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ALREADY_OWNER");
        }
        if (targetAssignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_ACTIVE");
        }

        // đổi vai trò của phân công được chọn thành OWNER
        targetAssignment.setRole(StaffRole.OWNER);
        staffAssignmentRepository.save(targetAssignment);

        // chủ cũ không bị xóa mà bị hạ xuống ADMIN
        Optional<StaffAssignment> currentOwnerAssignment = staffAssignmentRepository.findByEventId(eventId).stream()
                .filter(a -> a.getUserId().equals(uid)).findFirst();
        
        if (currentOwnerAssignment.isPresent()) {
            currentOwnerAssignment.get().setRole(StaffRole.ADMIN);
            staffAssignmentRepository.save(currentOwnerAssignment.get());
        }
    }

    /**
     * Trả về vai trò của người gọi trong sự kiện.
     * @throws ResponseStatusException 403 {@code NOT_IN_EVENT} khi người gọi không thuộc sự kiện
     */
    @Override
    public StaffRole getMyRole(String eventId, String authorization) {
        EventAccess access = accessOf(eventId, getUserIdFromAuth(authorization));
        if (access.role() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_IN_EVENT");
        }
        return access.role();
    }

    /** Trả về vai trò và tập quyền của người gọi trên sự kiện. */
    @Override
    public EventAccess getMyAccess(String eventId, String authorization) {
        return accessOf(eventId, getUserIdFromAuth(authorization));
    }
}
