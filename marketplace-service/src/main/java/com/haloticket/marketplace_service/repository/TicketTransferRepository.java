package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.TicketTransfer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTransferRepository extends JpaRepository<TicketTransfer, String> {
    Optional<TicketTransfer> findBySagaId(String sagaId);
}
