package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
