package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.TicketTransfer;
import java.util.Optional;
import java.util.List;
import com.haloticket.marketplace_service.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTransferRepository extends JpaRepository<TicketTransfer, String> {
    Optional<TicketTransfer> findBySagaId(String sagaId);
    List<TicketTransfer> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, TransferStatus status);
    Page<TicketTransfer> findByEventId(String eventId, Pageable pageable);
    Page<TicketTransfer> findByEventIdAndStatus(String eventId, TransferStatus status, Pageable pageable);
    long countByEventIdAndStatus(String eventId, TransferStatus status);
}
