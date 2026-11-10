package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Ticket;
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
    @Query("UPDATE Ticket t SET t.status = com.haloticket.ticket_service.enums.TicketStatus.USED, t.usedAt = :now WHERE t.id = :id AND t.status = com.haloticket.ticket_service.enums.TicketStatus.VALID")
    int markTicketUsedIfValid(@Param("id") String id, @Param("now") LocalDateTime now);

    boolean existsByTicketType_Id(String ticketTypeId);

    /** Primary-issued, non-revoked tickets per booking (same set as the stock reconciliation). Rows: bookingId, count. */
    @Query("SELECT t.bookingId, COUNT(t) FROM Ticket t WHERE t.bookingId IN :bookingIds "
            + "AND t.status <> com.haloticket.ticket_service.enums.TicketStatus.REVOKED "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY') GROUP BY t.bookingId")
    List<Object[]> countIssuedByBookingIds(@Param("bookingIds") java.util.Collection<String> bookingIds);

    /** Primary-sale tickets of a type that count against its stock (same rule as the stock reconciliation query). */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.ticketType.id = :typeId AND t.status <> com.haloticket.ticket_service.enums.TicketStatus.REVOKED "
            + "AND (t.acquisitionType IS NULL OR t.acquisitionType = 'PRIMARY')")
    long countPrimaryIssuedByTicketType(@Param("typeId") String typeId);
}
