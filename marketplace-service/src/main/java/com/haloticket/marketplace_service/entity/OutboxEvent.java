package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.OutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "outbox_events", indexes = @Index(name = "idx_market_outbox_status_created", columnList = "status,created_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "aggregate_type", nullable = false) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false) private String aggregateId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(nullable = false) private String topic;
    @Column(columnDefinition = "TEXT", nullable = false) private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OutboxStatus status;
    @Column(name = "retry_count", nullable = false) private Integer retryCount;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "processed_at") private Instant processedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); if (status == null) status = OutboxStatus.PENDING; if (retryCount == null) retryCount = 0; }
}
