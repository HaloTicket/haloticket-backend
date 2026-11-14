package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface EventViewLogRepository extends JpaRepository<EventViewLog, String> {

    @Query("SELECT COUNT(v) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :since")
    long countViewsSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT v.sessionId) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :since")
    long countUniqueViewsSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(v) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :from AND v.viewedAt < :to")
    long countViewsBetween(@Param("eventId") String eventId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT v.sessionId) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :from AND v.viewedAt < :to")
    long countUniqueViewsBetween(@Param("eventId") String eventId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
