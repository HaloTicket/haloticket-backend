package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.Booking;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.haloticket.booking_service.enums.BookingStatus;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
       interface ReportFact {
              String getBookingId();
              String getEventId();
              Long getTickets();
              BookingStatus getStatus();
       }
       interface FinanceFact { String getBookingId(); Double getAmount(); }
       @Query("SELECT b.id AS bookingId, b.totalAmount AS amount FROM Booking b WHERE b.eventId=:eventId AND b.status='PAID'")
       List<FinanceFact> findPaidFinanceFactsByEventId(@Param("eventId") String eventId);

       @Query("SELECT b.id AS bookingId, b.eventId AS eventId, COALESCE(SUM(i.quantity), 0) AS tickets, b.status AS status " +
              "FROM Booking b LEFT JOIN b.items i WHERE b.id IN :bookingIds GROUP BY b.id, b.eventId, b.status")
       List<ReportFact> findReportFactsByBookingIds(@Param("bookingIds") Collection<String> bookingIds);

       interface PaidCandidate {
              String getBookingId();
              String getBookingCode();
              LocalDateTime getPaidAt();
              Long getExpectedTickets();
       }

       /** Orders that should have tickets (PAID, CONFIRMED) paid in [from, to), oldest first. */
       @Query("SELECT b.id AS bookingId, b.bookingCode AS bookingCode, b.updatedAt AS paidAt, COALESCE(SUM(i.quantity), 0) AS expectedTickets " +
              "FROM Booking b LEFT JOIN b.items i " +
              "WHERE b.status IN (com.haloticket.booking_service.enums.BookingStatus.PAID, com.haloticket.booking_service.enums.BookingStatus.CONFIRMED) " +
              "AND b.updatedAt >= :from AND b.updatedAt < :to " +
              "GROUP BY b.id, b.bookingCode, b.updatedAt ORDER BY b.updatedAt ASC, b.id ASC")
       List<PaidCandidate> findPaidCandidates(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT b FROM Booking b WHERE b.id = :id")
       Optional<Booking> findByIdForUpdate(@Param("id") String id);

       Optional<Booking> findByBookingCode(String bookingCode);

       /** Bulk update on purpose: it does not fire @PreUpdate, so updated_at (the paid time the reconciliation reads) stays put. */
       @Modifying
       @Transactional
       @Query("UPDATE Booking b SET b.lastReissueAt = :now, b.reissueAttempts = COALESCE(b.reissueAttempts, 0) + 1 WHERE b.id = :id")
       int markReissueRequested(@Param("id") String id, @Param("now") LocalDateTime now);
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       List<Booking> findByStatusAndExpiredAtBefore(BookingStatus status, LocalDateTime dateTime);

       @Query("SELECT COUNT(DISTINCT b.customerId) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long countUniqueBuyersByEventId(@Param("eventId") String eventId);

       @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       double sumRevenueByEventId(@Param("eventId") String eventId);

       @Query("SELECT COALESCE(SUM(i.quantity), 0) FROM Booking b JOIN b.items i WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long sumTicketsSoldByEventId(@Param("eventId") String eventId);

       @Query("SELECT DISTINCT b FROM Booking b WHERE b.eventId = :eventId AND " +
                     "(:showtimeId IS NULL OR :showtimeId = '' OR b.showtimeId = :showtimeId) AND " +
                     "(LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :search, '%')))")
       Page<Booking> findByEventIdAndSearchAndShowtimeId(
                     @Param("eventId") String eventId,
                     @Param("search") String search,
                     @Param("showtimeId") String showtimeId,
                     Pageable pageable);
}
