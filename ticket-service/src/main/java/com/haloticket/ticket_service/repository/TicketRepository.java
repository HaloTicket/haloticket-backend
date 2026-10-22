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
    
    Optional<Ticket> findByQrCodeHash(String qrCodeHash);

    Page<Ticket> findByOwnerIdOrderByIssuedAtDesc(String ownerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Ticket> findWithLockById(String id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.status = com.haloticket.ticket_service.enums.TicketStatus.USED, t.usedAt = :now WHERE t.id = :id AND t.status = com.haloticket.ticket_service.enums.TicketStatus.VALID")
    int markTicketUsedIfValid(@Param("id") String id, @Param("now") LocalDateTime now);
}
