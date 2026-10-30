package com.haloticket.event_service.authz;

import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

/**
 * The single function that answers "what may this person do with this event", from staff_assignments, ACTIVE rows only.
 * userId is the user-service id (what staff_assignments holds), never the Keycloak subject.
 */
@Service
@RequiredArgsConstructor
public class EventPermissions {

    private final StaffAssignmentRepository assignments;
    private final EventRepository events;

    public EventAccess forUser(String userId, String eventId, boolean platformAdmin) {
        events.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        if (platformAdmin) {
            return new EventAccess(eventId, userId, true, StaffRole.OWNER, null, false, RolePermissions.all());
        }
        Optional<StaffAssignment> row = userId == null ? Optional.empty() : assignments.findByEventIdAndUserId(eventId, userId);
        if (row.isPresent()) {
            StaffAssignment assignment = row.get();
            if (assignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
                return new EventAccess(eventId, userId, false, null, assignment.getId(), true, Set.of());
            }
            return new EventAccess(eventId, userId, false, assignment.getRole(), assignment.getId(), false,
                    RolePermissions.of(assignment.getRole()));
        }
        return new EventAccess(eventId, userId, false, null, null, false, Set.of());
    }
}
