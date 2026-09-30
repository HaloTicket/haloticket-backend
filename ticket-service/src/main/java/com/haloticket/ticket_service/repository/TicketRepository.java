package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findByBookingId(String bookingId);
    
    Optional<Ticket> findByQrCodeHash(String qrCodeHash);

    Page<Ticket> findByOwnerIdOrderByIssuedAtDesc(String ownerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Ticket> findWithLockById(String id);
}
