package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Kho các sự kiện outbox chờ gửi sang Kafka. */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    /** Lấy tối đa 50 sự kiện outbox theo trạng thái, cũ nhất trước, cho mỗi lượt gửi. */
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
