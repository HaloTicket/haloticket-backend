package com.haloticket.ticket_service.repository;
import com.haloticket.ticket_service.entity.TicketOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface TicketOperationRepository extends JpaRepository<TicketOperation,String> { Optional<TicketOperation> findByOperationId(String operationId); }
