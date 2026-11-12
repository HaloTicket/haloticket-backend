package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import com.haloticket.event_service.enums.StaffAssignmentStatus;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    @Query("SELECT DISTINCT e FROM Event e LEFT JOIN FETCH e.category LEFT JOIN FETCH e.organizers")
    List<Event> findAllForAdminReport();

    @Query("SELECT DISTINCT e FROM Event e JOIN e.staffAssignments sa WHERE sa.userId = :userId AND sa.status = :status")
    List<Event> findEventsOfStaff(@Param("userId") String userId, @Param("status") StaffAssignmentStatus status);

    /** Events where the user has an ACTIVE assignment, any role (check-in staff included: they need the event in their list). */
    default List<Event> findMyEvents(String userId) {
        return findEventsOfStaff(userId, StaffAssignmentStatus.ACTIVE);
    }
}
