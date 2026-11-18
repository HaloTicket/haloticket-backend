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

/** Kho các lượt giữ tồn kho vé vào cửa chung trong lúc booking chờ thanh toán. */
@Repository
public interface TicketStockReservationRepository extends JpaRepository<TicketStockReservation, String> {

    /** Các lượt giữ chỗ của một booking. */
    List<TicketStockReservation> findByBookingId(String bookingId);

    /** Các lượt giữ chỗ của một booking, khóa ghi và sắp theo loại vé để các giao dịch khóa cùng thứ tự. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TicketStockReservation r WHERE r.bookingId = :bookingId ORDER BY r.ticketTypeId")
    List<TicketStockReservation> findByBookingIdForUpdate(@Param("bookingId") String bookingId);

    /** Lấy tối đa {@code limit} lượt giữ chỗ {@code HELD} đã hết hạn; dòng mà worker khác đang khóa bị bỏ qua nên nhiều instance cùng quét hết hạn được. */
    @Query(value = "SELECT * FROM ticket_stock_reservations WHERE status = 'HELD' AND expires_at < :now "
            + "ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<TicketStockReservation> findDueForExpiry(@Param("now") Instant now, @Param("limit") int limit);
}
