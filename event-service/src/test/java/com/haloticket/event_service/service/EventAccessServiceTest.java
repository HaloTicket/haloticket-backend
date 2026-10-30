package com.haloticket.event_service.service;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventAccessServiceTest {
    @Mock StaffAssignmentRepository assignments;
    @Mock EventRepository events;
    @Mock UserServiceClient users;
    EventAccessService access;

    @BeforeEach void setUp() {
        access = new EventAccessService(new EventPermissions(assignments, events), new CallerIds(users));
        lenient().when(events.findById(anyString())).thenAnswer(call -> Optional.of(event(call.getArgument(0))));
        lenient().when(users.getOrganizerEligibility("Bearer token"))
                .thenReturn(new OrganizerEligibilityResponse(false, "person", null, null));
    }

    @Test void eventRolesAreScopedAndActive() {
        when(assignments.findByEventIdAndUserId("A", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.OWNER, StaffAssignmentStatus.ACTIVE)));
        when(assignments.findByEventIdAndUserId("B", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.MANAGER, StaffAssignmentStatus.ACTIVE)));
        assertThat(access.requireWrite("A", "Bearer token")).isEqualTo("person");
        assertThat(access.requireRead("B", "Bearer token")).isEqualTo(StaffRole.MANAGER);
        assertThatThrownBy(() -> access.requireWrite("B", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
    }

    @Test void inactiveAssignmentIsRefusedEvenForTheOrganizerProfile() {
        // the person is also the organizer profile of the event: a removed member must still be refused
        Event event = event("A");
        event.setOrganizers(List.of(EventOrganizer.builder().userId("person").build()));
        when(events.findById("A")).thenReturn(Optional.of(event));
        when(assignments.findByEventIdAndUserId("A", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.OWNER, StaffAssignmentStatus.REVOKED)));
        assertThatThrownBy(() -> access.requireRead("A", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
    }

    @Test void aPersonOnlyInTheOrganizerProfileHasNoPermission() {
        Event event = event("A");
        event.setOrganizers(List.of(EventOrganizer.builder().userId("person").build()));
        when(events.findById("A")).thenReturn(Optional.of(event));
        when(assignments.findByEventIdAndUserId("A", "person")).thenReturn(Optional.empty());

        EventAccess result = access.forCaller("A", "Bearer token");
        assertThat(result.role()).isNull();
        assertThat(result.permissions()).isEmpty();
        assertThatThrownBy(() -> access.requireWrite("A", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
        assertThatThrownBy(() -> access.requireRead("A", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
    }

    @Test void aPlatformAdminPassesWithoutAnyAssignment() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("root", "n/a", "ROLE_ADMIN"));
        try {
            EventAccess result = access.forCaller("A", "Bearer token");
            assertThat(result.platformAdmin()).isTrue();
            assertThat(result.has(Permission.EVENT_EDIT)).isTrue();
            assertThat(access.requireWrite("A", "Bearer token")).isEqualTo("person");
            assertThat(access.requireRead("A", "Bearer token")).isEqualTo(StaffRole.OWNER);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Event event(String id) {
        Event event = new Event();
        event.setId(id);
        return event;
    }

    private StaffAssignment assignment(StaffRole role, StaffAssignmentStatus status) {
        return StaffAssignment.builder().event(new Event()).userId("person").role(role).status(status).build();
    }
}
