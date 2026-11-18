package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.SagaState;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Saga điều phối một thao tác chuyển nhượng (đăng bán lại hoặc tặng vé): lưu trạng thái, bước hiện tại, payload,
 * khóa idempotency, hạn xử lý và thông tin thử lại để scheduler tiếp tục hoặc bù trừ khi lỗi.
 */
@Entity @Table(name = "marketplace_sagas", indexes = @Index(name = "idx_saga_next_retry", columnList = "state,next_retry_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MarketplaceSaga {
    @Id private String id;
    @Column(nullable = false) private String type;
    @Column(name = "ticket_id", nullable = false) private String ticketId;
    @Column(name = "actor_id", nullable = false) private String actorId;
    @Column(name = "business_id") private String businessId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SagaState state;
    @Column(nullable = false) private String step;
    @Column(columnDefinition = "TEXT") private String payload;
    @Column(name = "idempotency_key", nullable = false, unique = true) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false) private String requestHash;
    @Column(name = "deadline_at") private Instant deadlineAt;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "retry_count", nullable = false) private Integer retryCount;
    @Column(name = "last_error") private String lastError;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** Đặt thời điểm tạo, cập nhật và số lần thử lại mặc định bằng 0 trước khi lưu mới. */
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; if (retryCount == null) retryCount = 0; }
    /** Cập nhật thời điểm sửa đổi trước mỗi lần lưu thay đổi. */
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
