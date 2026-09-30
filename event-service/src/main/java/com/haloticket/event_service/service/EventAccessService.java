package com.haloticket.event_service.service;

import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class EventAccessService {
    private final StaffAssignmentRepository assignments;
    private final EventRepository events;
    private final UserServiceClient users;

    public String actor(String authorization) {
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        try {
            var response = users.getOrganizerEligibility(authorization);
            if (response != null && response.userId() != null) return response.userId();
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE", failure);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    public StaffRole requireRead(String eventId, String authorization) {
        return requireRole(eventId, actor(authorization), false);
    }

    public String requireWrite(String eventId, String authorization) {
        String userId = actor(authorization);
        if (SecurityContextHolder.getContext().getAuthentication() != null &&
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            if (!events.existsById(eventId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
            return userId;
        }
        requireRole(eventId, userId, true);
        return userId;
    }

    public StaffRole requireRole(String eventId, String userId, boolean write) {
        StaffAssignment assignment = assignments.findByEventIdAndUserId(eventId, userId).orElse(null);
        if (assignment != null) {
            if (assignment.getStatus() != StaffAssignmentStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            StaffRole role = assignment.getRole();
            if (role == StaffRole.OWNER || role == StaffRole.ADMIN || (!write && role == StaffRole.MANAGER)) return role;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }
        var event = events.findById(eventId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        if (event.getOrganizers() != null && event.getOrganizers().stream().anyMatch(o -> userId.equals(o.getUserId()))) return StaffRole.OWNER;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }
}
