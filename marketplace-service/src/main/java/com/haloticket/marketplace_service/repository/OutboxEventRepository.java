package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.OutboxEvent;
import com.haloticket.marketplace_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent,String> { List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status); }
