package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.OutboxEvent;
import com.haloticket.marketplace_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
/** Repository bản ghi outbox. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent,String> { /** Lấy tối đa 50 bản ghi outbox theo trạng thái, cũ nhất trước. */ List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status); }
