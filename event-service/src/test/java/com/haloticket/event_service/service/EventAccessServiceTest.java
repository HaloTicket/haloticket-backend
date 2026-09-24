package com.haloticket.event_service.service;

import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventAccessServiceTest {
    @Mock StaffAssignmentRepository assignments;
    @Mock EventRepository events;
    @Mock UserServiceClient users;
    @InjectMocks EventAccessService access;

    @Test void eventRolesAreScopedAndActive() {
        when(users.getOrganizerEligibility("Bearer token"))
                .thenReturn(new OrganizerEligibilityResponse(false, "person", null, null));
        when(assignments.findByEventIdAndUserId("A", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.OWNER, StaffAssignmentStatus.ACTIVE)));
        when(assignments.findByEventIdAndUserId("B", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.MANAGER, StaffAssignmentStatus.ACTIVE)));
        assertThat(access.requireWrite("A", "Bearer token")).isEqualTo("person");
        assertThat(access.requireRead("B", "Bearer token")).isEqualTo(StaffRole.MANAGER);
        assertThatThrownBy(() -> access.requireWrite("B", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
    }

    @Test void inactiveAssignmentDoesNotUseLegacyOwnerFallback() {
        when(users.getOrganizerEligibility("Bearer token"))
                .thenReturn(new OrganizerEligibilityResponse(false, "person", null, null));
        when(assignments.findByEventIdAndUserId("A", "person"))
                .thenReturn(Optional.of(assignment(StaffRole.OWNER, StaffAssignmentStatus.REVOKED)));
        assertThatThrownBy(() -> access.requireRead("A", "Bearer token"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("FORBIDDEN_ROLE");
        verifyNoInteractions(events);
    }

    private StaffAssignment assignment(StaffRole role, StaffAssignmentStatus status) {
        return StaffAssignment.builder().event(new Event()).userId("person").role(role).status(status).build();
    }
}
