package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Truy vấn bản ghi outbox ({@code OutboxEvent}) chờ gửi lên Kafka. */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    /** Lấy tối đa 50 bản ghi theo trạng thái, cũ nhất trước. */
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);
    /** Lấy bản ghi mới nhất của một đối tượng theo loại sự kiện. */
    Optional<OutboxEvent> findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(String aggregateId, String eventType);
}
