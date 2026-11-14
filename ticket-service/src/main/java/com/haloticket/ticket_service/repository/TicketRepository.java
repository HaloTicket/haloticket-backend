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

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findByBookingId(String bookingId);
    
    Optional<Ticket> findByIssueKey(String issueKey);

    boolean existsByIssueKey(String issueKey);

    Optional<Ticket> findByQrCodeHash(String qrCodeHash);

    Page<Ticket> findByOwnerIdOrderByIssuedAtDesc(String ownerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Ticket> findWithLockById(String id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.status = :to, t.usedAt = :now WHERE t.id = :id AND t.status = :from")
    int moveTicketStatus(@Param("id") String id, @Param("now") LocalDateTime now,
                         @Param("from") TicketStatus from, @Param("to") TicketStatus to);

    /** VALID to USED in one statement; 0 rows when the ticket is not VALID any more (someone else used it first). */
    default int markTicketUsedIfValid(String id, LocalDateTime now) {
        return moveTicketStatus(id, now, TicketStatus.VALID, TicketStatus.USED);
    }

    boolean existsByTicketType_Id(String ticketTypeId);

    /** Rows: showtimeId, ticketTypeId, ticketTypeName, status, count; one row per showtime, type and status. */
    @Query("SELECT COALESCE(t.showtimeId, tt.showtimeId), tt.id, tt.name, t.status, COUNT(t) FROM Ticket t JOIN t.ticketType tt "
            + "WHERE t.eventId = :eventId AND t.status IN :statuses "
            + "GROUP BY COALESCE(t.showtimeId, tt.showtimeId), tt.id, tt.name, t.status")
    List<Object[]> countByShowtimeTypeAndStatus(@Param("eventId") String eventId, @Param("statuses") java.util.Collection<TicketStatus> statuses);

    /** Door-staff search: the text is a lower-cased fragment of the ticket id, the booking id, the seat reference or the type name. */
    @EntityGraph(attributePaths = {"ticketType", "seat", "seat.seatRow", "seat.seatRow.seatZone"})
    @Query("SELECT t FROM Ticket t JOIN t.ticketType tt WHERE t.eventId = :eventId AND t.status IN :statuses "
            + "AND (:showtimeId = '' OR COALESCE(t.showtimeId, tt.showtimeId) = :showtimeId) "
            + "AND (LOWER(t.id) LIKE :like OR LOWER(t.bookingId) LIKE :like OR LOWER(t.seatReference) LIKE :like "
            + "OR LOWER(tt.name) LIKE :like) ORDER BY t.issuedAt DESC")
    List<Ticket> searchForCheckin(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId,
                                  @Param("like") String like, @Param("statuses") java.util.Collection<TicketStatus> statuses,
                                  Pageable pageable);

    /** Primary-issued, non-revoked tickets per booking (same set as the stock reconciliation). Rows: bookingId, count. */
    @Query("SELECT t.bookingId, COUNT(t) FROM Ticket t WHERE t.bookingId IN :bookingIds "
            + "AND t.status <> :excluded "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY') GROUP BY t.bookingId")
    List<Object[]> countByBookingIdsExcluding(@Param("bookingIds") java.util.Collection<String> bookingIds,
                                              @Param("excluded") TicketStatus excluded);

    default List<Object[]> countIssuedByBookingIds(java.util.Collection<String> bookingIds) {
        return countByBookingIdsExcluding(bookingIds, TicketStatus.REVOKED);
    }

    /** Primary-sale tickets of a type that count against its stock (same rule as the stock reconciliation query). */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.ticketType.id = :typeId AND t.status <> :excluded "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY')")
    long countPrimaryByTicketTypeExcluding(@Param("typeId") String typeId, @Param("excluded") TicketStatus excluded);

    default long countPrimaryIssuedByTicketType(String typeId) {
        return countPrimaryByTicketTypeExcluding(typeId, TicketStatus.REVOKED);
    }
}
