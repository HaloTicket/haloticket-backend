package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Đối soát tài chính của một sự kiện (bảng {@code payout_reconciliations}): tổng thu, phí nền tảng, hoàn tiền, số tiền đang giữ và thông tin xác nhận. */
@Entity
@Table(name = "payout_reconciliations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutReconciliation {
    @Id @Column(name = "event_id") private String eventId;
    @Column(name = "organizer_user_id", nullable = false) private String organizerUserId;
    @Column(name = "gross_collected", nullable = false) private Long grossCollected;
    @Column(name = "platform_fees", nullable = false) private Long platformFees;
    @Column(name = "refunds", nullable = false) private Long refunds;
    @Column(name = "held_amount", nullable = false) private Long heldAmount;
    @Column(name = "confirmed_by") private String confirmedBy;
    @Column(name = "confirmed_at") private LocalDateTime confirmedAt;
    @Column(columnDefinition = "TEXT") private String reason;
    @Version private Long version;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    /** Trước khi lưu hoặc cập nhật: làm mới thời điểm cập nhật. */
    @PrePersist @PreUpdate void updateTimestamp() { updatedAt = LocalDateTime.now(); }
}
