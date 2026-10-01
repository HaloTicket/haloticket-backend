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

    @Query("SELECT DISTINCT e FROM Event e LEFT JOIN e.staffAssignments sa LEFT JOIN e.organizers o WHERE sa.userId = :userId OR o.userId = :userId")
    List<Event> findMyEvents(@Param("userId") String userId);
}
