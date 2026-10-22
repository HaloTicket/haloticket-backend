package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.TicketTransfer;
import java.util.Optional;
import java.util.List;
import com.haloticket.marketplace_service.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTransferRepository extends JpaRepository<TicketTransfer, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from TicketTransfer t where t.sagaId = :sagaId")
    Optional<TicketTransfer> findBySagaIdForUpdate(@org.springframework.data.repository.query.Param("sagaId") String sagaId);
    Optional<TicketTransfer> findBySagaId(String sagaId);
    List<TicketTransfer> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, TransferStatus status);
    Optional<TicketTransfer> findFirstByTicketIdAndStatus(String ticketId, TransferStatus status);
    Page<TicketTransfer> findByEventId(String eventId, Pageable pageable);
    Page<TicketTransfer> findByEventIdAndStatus(String eventId, TransferStatus status, Pageable pageable);
    long countByEventIdAndStatus(String eventId, TransferStatus status);
}
