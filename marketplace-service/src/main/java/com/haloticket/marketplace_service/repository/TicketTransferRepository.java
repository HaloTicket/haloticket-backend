package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.TicketTransfer;
import java.util.Optional;
import java.util.List;
import com.haloticket.marketplace_service.enums.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTransferRepository extends JpaRepository<TicketTransfer, String> {
    Optional<TicketTransfer> findBySagaId(String sagaId);
    List<TicketTransfer> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, TransferStatus status);
}
