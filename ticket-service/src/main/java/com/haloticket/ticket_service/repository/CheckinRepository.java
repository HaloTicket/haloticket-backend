package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Checkin;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CheckinRepository extends JpaRepository<Checkin, String> {
    List<Checkin> findByEventIdOrderByCheckedInAtDesc(String eventId);
    
    List<Checkin> findByEventIdAndTicket_ShowtimeIdOrderByCheckedInAtDesc(String eventId, String showtimeId);

    Optional<Checkin> findByTicketId(String ticketId);

    // The ticket, its type and its seat come in the same query (the list used to load them one row at a time).
    // showtimeId is "" for all showtimes: a null parameter has no type for the database. The showtime of a ticket is its own
    // showtime or, when it has none, the one of its ticket type.
    @EntityGraph(attributePaths = {"ticket", "ticket.ticketType", "ticket.seat", "ticket.seat.seatRow", "ticket.seat.seatRow.seatZone"})
    @Query("SELECT c FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) ORDER BY c.checkedInAt DESC")
    List<Checkin> listByEvent(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId);

    @EntityGraph(attributePaths = {"ticket", "ticket.ticketType", "ticket.seat", "ticket.seat.seatRow", "ticket.seat.seatRow.seatZone"})
    @Query(value = "SELECT c FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) ORDER BY c.checkedInAt DESC",
            countQuery = "SELECT COUNT(c) FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId)")
    Page<Checkin> pageByEvent(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId, Pageable pageable);
}
