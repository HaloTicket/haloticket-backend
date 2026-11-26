package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hibernate parses every @Query when the repositories start, so an invalid one fails here without a Postgres. The
 * full application context test needs Keycloak and a database, which is why this narrower one exists.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class RepositoryQueriesTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.event_service.entity")
    @EnableJpaRepositories("com.haloticket.event_service.repository")
    static class TestApp {}

    @Autowired EventRepository events;
    @Autowired VoucherRepository vouchers;
    @Autowired StaffAssignmentRepository assignments;

    @Test
    void theEnumIsPassedAsAParameterNotWrittenInTheQuery() {
        assertThat(events.findMyEvents("nobody")).isEmpty();
    }

    @Test
    void theVoucherRowLockQueryIsValid() {
        assertThat(vouchers.findByIdForUpdate("missing")).isEmpty();
    }
    @Test
    void announcementTeamContainsOnlyActiveAcceptedAssignmentsForTheRequestedEvent() {
        Event event = events.save(Event.builder().title("Test event").build());
        Event other = events.save(Event.builder().title("Other event").build());
        LocalDateTime cutoff = LocalDateTime.of(2026, 11, 25, 12, 0);
        assignment(event, "owner", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE, cutoff.minusDays(1));
        assignment(event, "member", StaffRole.CHECKIN_STAFF, StaffAssignmentStatus.ACTIVE, cutoff.minusDays(1));
        assignment(event, "member", StaffRole.ADMIN, StaffAssignmentStatus.ACTIVE, cutoff.minusDays(1));
        assignment(event, "pending", StaffRole.ADMIN, StaffAssignmentStatus.PENDING, cutoff.minusDays(1));
        assignment(event, "revoked", StaffRole.ADMIN, StaffAssignmentStatus.REVOKED, cutoff.minusDays(1));
        assignment(event, "new-member", StaffRole.ADMIN, StaffAssignmentStatus.ACTIVE, cutoff.plusDays(1));
        assignment(other, "other-owner", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE, cutoff.minusDays(1));
        var roles = List.of(StaffRole.values());

        assertThat(assignments.findNotificationTeamIds(event.getId(), StaffAssignmentStatus.ACTIVE, roles,
                cutoff, "", PageRequest.of(0, 1))).containsExactly("member");
        assertThat(assignments.findNotificationTeamIds(event.getId(), StaffAssignmentStatus.ACTIVE, roles,
                cutoff, "member", PageRequest.of(0, 5))).containsExactly("owner");
        assertThat(assignments.findAnnouncementStaff(event.getId(), StaffAssignmentStatus.ACTIVE, roles,
                cutoff, "", "member", PageRequest.of(0, 5))).containsExactly("owner");
        assertThat(assignments.findAnnouncementStaff(event.getId(), StaffAssignmentStatus.ACTIVE,
                List.of(StaffRole.CHECKIN_STAFF), cutoff, "", "", PageRequest.of(0, 5))).containsExactly("member");
        assertThat(assignments.findAnnouncementStaff(event.getId(), StaffAssignmentStatus.ACTIVE, roles,
                cutoff, "member", "", PageRequest.of(0, 5))).containsExactly("member");
        assertThat(assignments.findAnnouncementStaff(event.getId(), StaffAssignmentStatus.ACTIVE, roles,
                cutoff, "other-owner", "", PageRequest.of(0, 5))).isEmpty();
    }

    private void assignment(Event event, String userId, StaffRole role, StaffAssignmentStatus status, LocalDateTime time) {
        assignments.save(StaffAssignment.builder().event(event).userId(userId).role(role).status(status).assignedAt(time).build());
    }
}
