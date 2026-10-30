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

    /** The caller's user-service id; a bad token is 401, user-service being down is 503. */
    private String getUserIdFromAuth(String authorization) {
        return callers.userId(authorization);
    }

    private EventAccess accessOf(String eventId, String userId) {
        return permissions.forUser(userId, eventId, EventAccessService.isPlatformAdmin());
    }

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

    /** An assignment id on the path must belong to the event on the path (404 otherwise, as if it did not exist). */
    private void requireAssignmentOfEvent(StaffAssignment assignment, String eventId) {
        if (assignment.getEvent() == null || !eventId.equals(assignment.getEvent().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
        }
    }

    @Override
    public List<EventMemberResponse> getEventMembers(String eventId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        // allow OWNER, ADMIN, MANAGER to view members
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
                    // ignore, fallback
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
        // an invitation never makes an owner: ownership only changes hands through transfer-owner
        if (request.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(422), "CANNOT_INVITE_OWNER");
        }
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isEmpty() || !email.contains("@") || email.length() > 254) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        // the caller (an ACTIVE member of the event) cannot invite their own address
        staffAssignmentRepository.findByEventIdAndUserId(eventId, uid)
                .filter(self -> self.getStatus() == StaffAssignmentStatus.ACTIVE)
                .ifPresent(self -> {
                    String callerEmail = self.getEmail();
                    if (callerEmail == null || callerEmail.isBlank()) {
                        try {
                            InternalUserResponse callerUser = userInternalServiceClient.getUserById(uid);
                            callerEmail = callerUser == null ? null : callerUser.getEmail();
                        } catch (Exception ignored) {
                            // the address is unknown: nothing to compare with
                        }
                    }
                    if (callerEmail != null && callerEmail.equalsIgnoreCase(email)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_INVITE_YOURSELF");
                    }
                });

        StaffAssignment existing = pick(staffAssignmentRepository.findAllByEventIdAndEmail(eventId, email));
        requireInvitable(existing);
        requireRoomForAnotherSend(eventId);

        // call User Service to find, or create without a usable password, the invited person's account
        EventMemberInviteRequest lookup = new EventMemberInviteRequest();
        lookup.setEmail(email);
        lookup.setRole(request.getRole());
        UserInviteResponse inviteRes = userInternalServiceClient.inviteUser(lookup);
        if (existing == null) {
            // rows written without an email (owner backfill) are found by the account instead
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
            // with the unique (event, person) index in place, a parallel invitation of the same person loses here
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_PENDING");
        }

        publishInvitation(event, assignment, inviteRes.isNewUser(), token);
        return toResponse(assignment, inviteRes.getAvatar());
    }

    /** One row stands for a person: when older data has several, the member, then the open invitation, wins. */
    private static StaffAssignment pick(List<StaffAssignment> rows) {
        if (rows == null || rows.isEmpty()) return null;
        return rows.stream().filter(r -> r.getStatus() == StaffAssignmentStatus.ACTIVE).findFirst()
                .or(() -> rows.stream().filter(r -> r.getStatus() == StaffAssignmentStatus.PENDING).findFirst())
                .orElse(rows.get(0));
    }

    /** The per-event limit on invitations and re-sends in 24 hours, counted from the rows themselves. */
    private void requireRoomForAnotherSend(String eventId) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        if (staffAssignmentRepository.invitationsSentSince(eventId, since) >= invitations.getMaxPerEventPerDay()) {
            LocalDateTime first = staffAssignmentRepository.firstInvitationSince(eventId, since);
            long wait = first == null ? 3600 : java.time.Duration.between(LocalDateTime.now(), first.plusHours(24)).getSeconds();
            throw new RateLimitedException(wait);
        }
    }

    /** A person who is already a member, or has an invitation still waiting, is not invited again. */
    private void requireInvitable(StaffAssignment existing) {
        if (existing == null) return;
        StaffAssignmentStatus status = existing.getStatus();
        if (status == StaffAssignmentStatus.PENDING && "PENDING".equals(existing.statusLabel())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INVITATION_PENDING");
        }
        if (status == StaffAssignmentStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ALREADY_MEMBER");
        }
        // an expired or declined invitation, or a removed member, is invited again on the same row
    }

    private String issueToken(StaffAssignment assignment, LocalDateTime now) {
        String token = InvitationTokens.newToken();
        assignment.setStatus(StaffAssignmentStatus.PENDING);
        assignment.setInvitationTokenHash(InvitationTokens.hash(token));
        assignment.setInvitationExpiresAt(now.plusHours(invitations.getTtlHours()));
        return token;
    }

    private void clearToken(StaffAssignment assignment) {
        assignment.setInvitationTokenHash(null);
        assignment.setInvitationExpiresAt(null);
    }

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
        // a declined invitation that is sent again starts a new count
        int sent = declined || assignment.getInvitationResendCount() == null ? 0 : assignment.getInvitationResendCount();
        if (assignment.getInvitedAt() != null) {
            LocalDateTime next = assignment.getInvitedAt().plusSeconds(invitations.getResendMinSeconds());
            if (next.isAfter(now)) throw new RateLimitedException(java.time.Duration.between(now, next).getSeconds() + 1);
        }
        if (sent >= invitations.getMaxResends()) {
            // no more re-sends of this invitation: it can be invited again once it has expired
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

    /** The invitation the signed-in person may answer: anything else is the same 404, so nothing about it leaks. */
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

    @Override
    @Transactional(readOnly = true)
    public MemberInvitationPreviewResponse previewInvitation(String token, String authorization) {
        StaffAssignment assignment = openInvitation(token, authorization);
        return new MemberInvitationPreviewResponse(assignment.getEvent().getId(), assignment.getEvent().getTitle(),
                assignment.getRole(), assignment.getInvitationExpiresAt(), "PENDING");
    }

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

    @Override
    @Transactional
    public void declineInvitation(String token, String authorization) {
        StaffAssignment assignment = openInvitation(token, authorization);
        assignment.setStatus(StaffAssignmentStatus.DECLINED);
        clearToken(assignment);
        staffAssignmentRepository.save(assignment);
    }

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
        // only a member can be switched off and on; an invitation is answered by its recipient (or cancelled with a delete)
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

        // change target assignment role to OWNER
        targetAssignment.setRole(StaffRole.OWNER);
        staffAssignmentRepository.save(targetAssignment);

        // remove old owner from StaffAssignment or demote to ADMIN
        Optional<StaffAssignment> currentOwnerAssignment = staffAssignmentRepository.findByEventId(eventId).stream()
                .filter(a -> a.getUserId().equals(uid)).findFirst();
        
        if (currentOwnerAssignment.isPresent()) {
            currentOwnerAssignment.get().setRole(StaffRole.ADMIN);
            staffAssignmentRepository.save(currentOwnerAssignment.get());
        }
    }

    @Override
    public StaffRole getMyRole(String eventId, String authorization) {
        EventAccess access = accessOf(eventId, getUserIdFromAuth(authorization));
        if (access.role() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_IN_EVENT");
        }
        return access.role();
    }

    @Override
    public EventAccess getMyAccess(String eventId, String authorization) {
        return accessOf(eventId, getUserIdFromAuth(authorization));
    }
}
