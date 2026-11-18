package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Thực thể hộp thư đi (outbox) của thay đổi chính sách chuyển nhượng (bảng {@code transfer_policy_outbox}): thông điệp chờ phát lên Kafka kèm trạng thái, số lần thử lại và thời điểm thử kế tiếp. */
@Entity @Table(name = "transfer_policy_outbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransferPolicyOutboxEvent {
    @Id private String id;
    @Column(name = "event_id", nullable = false) private String eventId;
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
}
