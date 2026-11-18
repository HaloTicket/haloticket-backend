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

/** Kho truy vấn các lượt check-in đã thành công của người vào cửa. */
@Repository
public interface CheckinRepository extends JpaRepository<Checkin, String> {
    /** Các lượt check-in của một sự kiện, mới nhất trước. */
    List<Checkin> findByEventIdOrderByCheckedInAtDesc(String eventId);
    
    /** Các lượt check-in của một sự kiện theo suất diễn của vé, mới nhất trước. */
    List<Checkin> findByEventIdAndTicket_ShowtimeIdOrderByCheckedInAtDesc(String eventId, String showtimeId);

    /** Tìm lượt check-in của một vé; mỗi vé chỉ có tối đa một lượt. */
    Optional<Checkin> findByTicketId(String ticketId);

    /**
     * Danh sách lượt check-in của sự kiện, nạp kèm vé, loại vé và ghế trong cùng một truy vấn (trước đây nạp từng dòng một).
     * {@code showtimeId} rỗng nghĩa là mọi suất diễn vì tham số null không có kiểu với cơ sở dữ liệu. Suất diễn của vé là
     * suất của chính vé đó, hoặc của loại vé nếu vé không có.
     */
    @EntityGraph(attributePaths = {"ticket", "ticket.ticketType", "ticket.seat", "ticket.seat.seatRow", "ticket.seat.seatRow.seatZone"})
    @Query("SELECT c FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) ORDER BY c.checkedInAt DESC")
    List<Checkin> listByEvent(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId);

    /** Phiên bản phân trang của {@code listByEvent}, cùng điều kiện lọc và thứ tự mới nhất trước. */
    @EntityGraph(attributePaths = {"ticket", "ticket.ticketType", "ticket.seat", "ticket.seat.seatRow", "ticket.seat.seatRow.seatZone"})
    @Query(value = "SELECT c FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) ORDER BY c.checkedInAt DESC",
            countQuery = "SELECT COUNT(c) FROM Checkin c JOIN c.ticket t JOIN t.ticketType tt WHERE c.eventId = :eventId "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId)")
    Page<Checkin> pageByEvent(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId, Pageable pageable);
}
