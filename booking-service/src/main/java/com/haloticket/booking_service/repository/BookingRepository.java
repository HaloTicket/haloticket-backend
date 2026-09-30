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

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT b FROM Booking b WHERE b.id = :id")
       Optional<Booking> findByIdForUpdate(@Param("id") String id);

       Optional<Booking> findByBookingCode(String bookingCode);
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       List<Booking> findByStatusAndExpiredAtBefore(BookingStatus status, LocalDateTime dateTime);

       @Query("SELECT COUNT(DISTINCT b.customerId) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long countUniqueBuyersByEventId(@Param("eventId") String eventId);

       @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       double sumRevenueByEventId(@Param("eventId") String eventId);

       @Query("SELECT COALESCE(SUM(i.quantity), 0) FROM Booking b JOIN b.items i WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long sumTicketsSoldByEventId(@Param("eventId") String eventId);

       @Query("SELECT DISTINCT b FROM Booking b WHERE b.eventId = :eventId AND " +
                     "(:showtimeId IS NULL OR :showtimeId = '' OR CAST(b.createdAt AS text) LIKE CONCAT(:showtimeId, '%')) AND " +
                     "(LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :search, '%')))")
       Page<Booking> findByEventIdAndSearchAndShowtimeId(
                     @Param("eventId") String eventId,
                     @Param("search") String search,
                     @Param("showtimeId") String showtimeId,
                     Pageable pageable);
}
