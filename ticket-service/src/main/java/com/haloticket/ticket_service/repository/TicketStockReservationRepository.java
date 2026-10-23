package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.TicketStockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TicketStockReservationRepository extends JpaRepository<TicketStockReservation, String> {

    List<TicketStockReservation> findByBookingId(String bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TicketStockReservation r WHERE r.bookingId = :bookingId ORDER BY r.ticketTypeId")
    List<TicketStockReservation> findByBookingIdForUpdate(@Param("bookingId") String bookingId);

    /** Rows another worker already locks are skipped, so several instances can run the expiry sweep. */
    @Query(value = "SELECT * FROM ticket_stock_reservations WHERE status = 'HELD' AND expires_at < :now "
            + "ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<TicketStockReservation> findDueForExpiry(@Param("now") Instant now, @Param("limit") int limit);
}
