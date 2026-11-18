package com.haloticket.ticket_service.repository;
import com.haloticket.ticket_service.entity.TicketOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
/** Kho các thao tác chợ vé (khóa, mở khóa, chuyển quyền sở hữu) trên từng vé. */
public interface TicketOperationRepository extends JpaRepository<TicketOperation,String> { /** Tìm thao tác theo mã thao tác (idempotency key của lệnh). */ Optional<TicketOperation> findByOperationId(String operationId); }
