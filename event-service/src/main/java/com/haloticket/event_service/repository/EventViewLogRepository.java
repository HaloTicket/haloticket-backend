package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** Truy vấn nhật ký lượt xem sự kiện {@code EventViewLog}, phục vụ thống kê. */
@Repository
public interface EventViewLogRepository extends JpaRepository<EventViewLog, String> {

    /** Đếm tổng lượt xem của sự kiện kể từ mốc {@code since}. */
    @Query("SELECT COUNT(v) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :since")
    long countViewsSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    /** Đếm số phiên (session) khác nhau đã xem sự kiện kể từ mốc {@code since}. */
    @Query("SELECT COUNT(DISTINCT v.sessionId) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :since")
    long countUniqueViewsSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    /** Đếm tổng lượt xem của sự kiện trong khoảng [{@code from}, {@code to}). */
    @Query("SELECT COUNT(v) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :from AND v.viewedAt < :to")
    long countViewsBetween(@Param("eventId") String eventId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Đếm số phiên khác nhau đã xem sự kiện trong khoảng [{@code from}, {@code to}). */
    @Query("SELECT COUNT(DISTINCT v.sessionId) FROM EventViewLog v WHERE v.eventId = :eventId AND v.viewedAt >= :from AND v.viewedAt < :to")
    long countUniqueViewsBetween(@Param("eventId") String eventId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
