package com.haloticket.payment_service.entity;

import com.haloticket.payment_service.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Yêu cầu hoàn tiền của một thanh toán (bảng {@code refunds}): số tiền, lý do và trạng thái. */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    private Double amount;
    private String reason;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu: gán thời điểm tạo, cập nhật và trạng thái {@code PENDING} nếu chưa có. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = RefundStatus.PENDING;
    }

    /** Trước khi cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
