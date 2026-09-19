package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.client.UserInternalServiceClient;
import com.haloticket.event_service.client.UserInviteResponse;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.dto.response.InternalUserResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventMemberServiceImpl implements EventMemberService {

    private final StaffAssignmentRepository staffAssignmentRepository;
    private final EventRepository eventRepository;
    private final UserServiceClient userServiceClient;
    private final UserInternalServiceClient userInternalServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private String getUserIdFromAuth(String authorization) {
        try {
            OrganizerEligibilityResponse eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility == null || eligibility.userId() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
            }
            return eligibility.userId();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private StaffRole requireRole(String eventId, String userId, StaffRole... allowedRoles) {
        StaffAssignment assignment = staffAssignmentRepository.findByEventIdAndUserId(eventId, userId).orElse(null);
        
        if (assignment != null) {
            if (assignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE");
            }
            for (StaffRole role : allowedRoles) {
                if (assignment.getRole() == role) {
                    return assignment.getRole();
                }
            }
        } else {
            // fallback for events created before StaffAssignment auto-creation was added
            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
            
            boolean isOrganizer = event.getOrganizers() != null && event.getOrganizers().stream()
                    .anyMatch(o -> userId.equals(o.getUserId()));
                    
            if (isOrganizer) {
                for (StaffRole role : allowedRoles) {
                    if (StaffRole.OWNER == role) {
                        return StaffRole.OWNER;
                    }
                }
            }
        }
        
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    @Override
    public List<EventMemberResponse> getEventMembers(String eventId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        // allow OWNER, ADMIN, MANAGER to view members
        requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN, StaffRole.MANAGER);

        List<StaffAssignment> assignments = staffAssignmentRepository.findByEventId(eventId);
        List<EventMemberResponse> responses = assignments.stream().map(a -> {
            String avatarUrl = null;
            if (a.getUserId() != null) {
                try {
                    InternalUserResponse userResp = userInternalServiceClient.getUserById(a.getUserId());
                    if (userResp != null && userResp.getAvatarUrl() != null) {
                        avatarUrl = userResp.getAvatarUrl();
                    }
                } catch (Exception ex) {
                    // ignore, fallback to null
                }
            }
            return EventMemberResponse.builder()
                .id(a.getId())
                .name(a.getName())
                .email(a.getEmail())
                .avatar(avatarUrl)
                .role(a.getRole())
                .status(a.getStatus().name())
                .addedAt(a.getAssignedAt())
                .build();
        }).collect(Collectors.toCollection(java.util.ArrayList::new));
                
        // fallback for organizers who don't have a StaffAssignment record
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event != null && event.getOrganizers() != null) {
            for (EventOrganizer org : event.getOrganizers()) {
                boolean alreadyAssigned = assignments.stream().anyMatch(a -> org.getUserId().equals(a.getUserId()));
                if (!alreadyAssigned) {
                    String name = org.getName();
                    String email = name + " (Organizer)";
                    String avatar = org.getAvatarUrl();
                    
                    try {
                        InternalUserResponse userResp = userInternalServiceClient.getUserById(org.getUserId());
                        if (userResp != null) {
                            if (userResp.getFullName() != null) name = userResp.getFullName();
                            if (userResp.getEmail() != null) email = userResp.getEmail();
                            if (userResp.getAvatarUrl() != null) avatar = userResp.getAvatarUrl();
                        }
                    } catch (Exception ex) {
                        // ignore, fallback to defaults
                    }

                    responses.add(0, EventMemberResponse.builder()
                            .id(org.getId())
                            .name(name)
                            .email(email) 
                            .avatar(avatar)
                            .role(StaffRole.OWNER)
                            .status(com.haloticket.event_service.enums.StaffAssignmentStatus.ACTIVE.name())
                            .addedAt(event.getCreatedAt())
                            .build());
                }
            }
        }

        return responses;
    }

    @Override
    public EventMemberResponse inviteMember(String eventId, EventMemberInviteRequest request, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        if (currentRole == StaffRole.ADMIN && request.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_CANNOT_ASSIGN_OWNER");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        // verify not self-inviting
        if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
            String ownerUserId = event.getOrganizers().get(0).getUserId();
            try {
                InternalUserResponse ownerUser = userInternalServiceClient.getUserById(ownerUserId);
                if (ownerUser != null && ownerUser.getEmail() != null && ownerUser.getEmail().equalsIgnoreCase(request.getEmail())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_INVITE_YOURSELF");
                }
            } catch (Exception ex) {
                if (ex instanceof ResponseStatusException) throw ex;
                // ignore other exceptions
            }
        }

        // check if already invited
        staffAssignmentRepository.findByEventIdAndEmail(eventId, request.getEmail()).ifPresent(a -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER_ALREADY_IN_EVENT");
        });

        // call User Service to invite/create user
        UserInviteResponse inviteRes = userInternalServiceClient.inviteUser(request);

        StaffAssignment assignment = StaffAssignment.builder()
                .event(event)
                .userId(inviteRes.getUserId())
                .email(inviteRes.getEmail())
                .name(inviteRes.getName())
                .role(request.getRole())
                .status(StaffAssignmentStatus.ACTIVE)
                .build();
        
        assignment = staffAssignmentRepository.save(assignment);

        // send email via notification service
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventTitle", event.getTitle());
        payload.put("email", inviteRes.getEmail());
        payload.put("name", inviteRes.getName());
        payload.put("role", request.getRole().name());
        payload.put("isNewUser", inviteRes.isNewUser());
        if (inviteRes.isNewUser()) {
            payload.put("generatedPassword", inviteRes.getGeneratedPassword());
        }

        try {
            kafkaTemplate.send("notification.event.member.invitation", payload);
        } catch (Exception e) {
            System.err.println("Failed to send kafka notification: " + e.getMessage());
        }

        return EventMemberResponse.builder()
                .id(assignment.getId())
                .name(assignment.getName())
                .email(assignment.getEmail())
                .avatar(inviteRes.getAvatar())
                .role(assignment.getRole())
                .status(assignment.getStatus().name())
                .addedAt(assignment.getAssignedAt())
                .build();
    }

    @Override
    public EventMemberResponse changeMemberRole(String eventId, String assignmentId, StaffRole newRole, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffRole currentRole = requireRole(eventId, uid, StaffRole.OWNER, StaffRole.ADMIN);

        StaffAssignment assignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }
        if (newRole == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_ASSIGN_OWNER");
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
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }

        assignment.setStatus(StaffAssignmentStatus.valueOf(newStatus));
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
        
        if (assignment.getRole() == StaffRole.OWNER && currentRole != StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER");
        }

        staffAssignmentRepository.delete(assignment);
    }

    @Override
    public void transferOwner(String eventId, String assignmentId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        requireRole(eventId, uid, StaffRole.OWNER);

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        StaffAssignment targetAssignment = staffAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND"));

        if (!targetAssignment.getEvent().getId().equals(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ASSIGNMENT");
        }

        if (targetAssignment.getRole() == StaffRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ALREADY_OWNER");
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

        // update EventOrganizer to the new owner
        if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
            EventOrganizer org = event.getOrganizers().get(0);
            org.setUserId(targetAssignment.getUserId());
            org.setName(targetAssignment.getName());
            eventRepository.save(event);
        }
    }

    @Override
    public StaffRole getMyRole(String eventId, String authorization) {
        String uid = getUserIdFromAuth(authorization);
        StaffAssignment assignment = staffAssignmentRepository.findByEventIdAndUserId(eventId, uid).orElse(null);
        
        if (assignment != null) {
            return assignment.getRole();
        }
        
        // fallback for events created before StaffAssignment auto-creation was added
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
                
        boolean isOrganizer = event.getOrganizers() != null && event.getOrganizers().stream()
                .anyMatch(o -> uid.equals(o.getUserId()));
                
        if (isOrganizer) {
            return StaffRole.OWNER;
        }
        
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_IN_EVENT");
    }
}
