package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    @Query("SELECT DISTINCT e FROM Event e LEFT JOIN FETCH e.category LEFT JOIN FETCH e.organizers")
    List<Event> findAllForAdminReport();

    /** Events where the user has an ACTIVE assignment, any role (check-in staff included: they need the event in their list). */
    @Query("SELECT DISTINCT e FROM Event e JOIN e.staffAssignments sa WHERE sa.userId = :userId AND sa.status = com.haloticket.event_service.enums.StaffAssignmentStatus.ACTIVE")
    List<Event> findMyEvents(@Param("userId") String userId);

    /** Legacy owners: events whose organizer profile is the user's and where the user has no assignment row at all. */
    @Query("SELECT DISTINCT e FROM Event e JOIN e.organizers o WHERE o.userId = :userId AND NOT EXISTS (SELECT 1 FROM StaffAssignment s WHERE s.event = e AND s.userId = :userId)")
    List<Event> findLegacyOwnedEvents(@Param("userId") String userId);
}
