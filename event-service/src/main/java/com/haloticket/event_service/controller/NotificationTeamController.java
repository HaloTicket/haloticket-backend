package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.RolePermissions;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/internal/notification-teams")
public class NotificationTeamController {
    private static final ZoneId STORED_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<StaffRole> TEAM_ROLES = Arrays.stream(StaffRole.values())
            .filter(role -> !RolePermissions.of(role).isEmpty()).toList();

    private final EventRepository events;
    private final StaffAssignmentRepository assignments;
    private final EventAccessService access;
    private final byte[] internalToken;

    public NotificationTeamController(EventRepository events, StaffAssignmentRepository assignments,
            EventAccessService access,
            @Value("${authz.event-scope.internal-token:}") String internalToken) {
        this.events = events;
        this.assignments = assignments;
        this.access = access;
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{eventId}")
    public List<String> teamIds(@RequestHeader(name = "X-Internal-Token", required = false) String token,
                                @PathVariable String eventId, @RequestParam Instant cutoff,
                                @RequestParam(defaultValue = "") String after,
                                @RequestParam(defaultValue = "200") int limit) {
        requireInternal(token);
        if (limit < 1 || limit > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        if (!events.existsById(eventId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");

        return assignments.findNotificationTeamIds(eventId, StaffAssignmentStatus.ACTIVE, TEAM_ROLES,
                LocalDateTime.ofInstant(cutoff, STORED_TIME_ZONE), after, PageRequest.of(0, limit));
    }

    @GetMapping("/{eventId}/access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void announcementAccess(@RequestHeader(name = "X-Internal-Token", required = false) String token,
                                   @PathVariable String eventId, @RequestParam String actorId,
                                   @RequestParam(defaultValue = "") String showtimeId) {
        requireInternal(token);
        access.requireAnnouncementActor(eventId, actorId);
        if (!showtimeId.isEmpty() && !events.hasNotificationShowtime(eventId, showtimeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND");
        }
    }

    @GetMapping("/{eventId}/staff")
    public List<String> announcementStaff(@RequestHeader(name = "X-Internal-Token", required = false) String token,
            @PathVariable String eventId, @RequestParam String actorId, @RequestParam Instant cutoff,
            @RequestParam(required = false) StaffRole role, @RequestParam(defaultValue = "") String memberId,
            @RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "200") int limit) {
        requireInternal(token);
        access.requireAnnouncementActor(eventId, actorId);
        if (limit < 1 || limit > 200 || (role != null && (!TEAM_ROLES.contains(role) || !memberId.isEmpty()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STAFF_SELECTOR");
        }
        return assignments.findAnnouncementStaff(eventId, StaffAssignmentStatus.ACTIVE,
                role == null ? TEAM_ROLES : List.of(role), LocalDateTime.ofInstant(cutoff, STORED_TIME_ZONE),
                memberId, after, PageRequest.of(0, limit));
    }

    private void requireInternal(String token) {
        if (internalToken.length == 0 || token == null
                || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }
}
