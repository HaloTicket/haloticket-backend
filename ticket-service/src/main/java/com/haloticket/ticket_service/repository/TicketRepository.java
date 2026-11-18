package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Ticket;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import com.haloticket.ticket_service.enums.TicketStatus;

import java.util.List;
import java.time.LocalDateTime;

/** Kho vé đã phát hành, kèm các thao tác cập nhật trạng thái nguyên tử và truy vấn phục vụ check-in và đối soát. */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    /** Các vé của một booking. */
    List<Ticket> findByBookingId(String bookingId);
    
    /** Tìm vé theo khóa phát hành idempotent {@code bookingId:typeId:số thứ tự}. */
    Optional<Ticket> findByIssueKey(String issueKey);

    /** Kiểm tra đã có vé với khóa phát hành này chưa. */
    boolean existsByIssueKey(String issueKey);

    /** Tìm vé theo mã QR. */
    Optional<Ticket> findByQrCodeHash(String qrCodeHash);

    /** Danh sách vé của một chủ vé, phân trang, mới phát hành trước. */
    Page<Ticket> findByOwnerIdOrderByIssuedAtDesc(String ownerId, Pageable pageable);

    /** Nạp vé bằng khóa ghi (pessimistic) để thao tác không bị giẫm lên. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Ticket> findWithLockById(String id);

    /**
     * Đổi trạng thái vé từ {@code from} sang {@code to} trong một câu lệnh, ghi {@code usedAt}; chỉ cập nhật khi
     * vé đang ở đúng {@code from}. Trả số dòng cập nhật và xóa persistence context.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.status = :to, t.usedAt = :now WHERE t.id = :id AND t.status = :from")
    int moveTicketStatus(@Param("id") String id, @Param("now") LocalDateTime now,
                         @Param("from") TicketStatus from, @Param("to") TicketStatus to);

    /** Chuyển vé từ {@code VALID} sang {@code USED} trong một câu lệnh; trả 0 dòng khi vé không còn {@code VALID} (người khác đã dùng trước). */
    default int markTicketUsedIfValid(String id, LocalDateTime now) {
        return moveTicketStatus(id, now, TicketStatus.VALID, TicketStatus.USED);
    }

    /** Kiểm tra loại vé đã có vé phát hành chưa. */
    boolean existsByTicketType_Id(String ticketTypeId);

    /** Đếm vé theo suất diễn, loại vé và trạng thái. Mỗi dòng gồm: showtimeId, ticketTypeId, ticketTypeName, status, count. */
    @Query("SELECT COALESCE(t.showtimeId, tt.showtimeId), tt.id, tt.name, t.status, COUNT(t) FROM Ticket t JOIN t.ticketType tt "
            + "WHERE t.eventId = :eventId AND t.status IN :statuses "
            + "GROUP BY COALESCE(t.showtimeId, tt.showtimeId), tt.id, tt.name, t.status")
    List<Object[]> countByShowtimeTypeAndStatus(@Param("eventId") String eventId, @Param("statuses") java.util.Collection<TicketStatus> statuses);

    /** Tìm vé cho nhân viên soát vé: {@code like} là mảnh chữ thường của mã vé, mã booking, mã ghế hoặc tên loại vé. */
    @EntityGraph(attributePaths = {"ticketType", "seat", "seat.seatRow", "seat.seatRow.seatZone"})
    @Query("SELECT t FROM Ticket t JOIN t.ticketType tt WHERE t.eventId = :eventId AND t.status IN :statuses "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) "
            + "AND (LOWER(t.id) LIKE :like OR LOWER(t.bookingId) LIKE :like OR LOWER(t.seatReference) LIKE :like "
            + "OR LOWER(tt.name) LIKE :like) ORDER BY t.issuedAt DESC")
    List<Ticket> searchForCheckin(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId,
                                  @Param("like") String like, @Param("statuses") java.util.Collection<TicketStatus> statuses,
                                  Pageable pageable);

    /** Đếm vé phát hành lần đầu và chưa bị thu hồi theo booking (cùng tập vé với đối soát tồn kho). Mỗi dòng gồm: bookingId, count. */
    @Query("SELECT t.bookingId, COUNT(t) FROM Ticket t WHERE t.bookingId IN :bookingIds "
            + "AND t.status <> :excluded "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY') GROUP BY t.bookingId")
    List<Object[]> countByBookingIdsExcluding(@Param("bookingIds") java.util.Collection<String> bookingIds,
                                              @Param("excluded") TicketStatus excluded);

    /** Đếm vé đã phát hành lần đầu (không tính vé {@code REVOKED}) theo danh sách booking. */
    default List<Object[]> countIssuedByBookingIds(java.util.Collection<String> bookingIds) {
        return countByBookingIdsExcluding(bookingIds, TicketStatus.REVOKED);
    }

    /** Đếm vé bán lần đầu của một loại vé tính vào tồn kho (cùng quy tắc với truy vấn đối soát tồn kho). */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.ticketType.id = :typeId AND t.status <> :excluded "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY')")
    long countPrimaryByTicketTypeExcluding(@Param("typeId") String typeId, @Param("excluded") TicketStatus excluded);

    /** Đếm vé bán lần đầu của loại vé, không tính vé {@code REVOKED}. */
    default long countPrimaryIssuedByTicketType(String typeId) {
        return countPrimaryByTicketTypeExcluding(typeId, TicketStatus.REVOKED);
    }
}
