package com.haloticket.payment_service.entity;

import com.haloticket.payment_service.enums.PayoutStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Yêu cầu chi trả cho ban tổ chức (bảng {@code payouts}): số tiền, trạng thái, ảnh chụp tài khoản nhận và chính sách tại thời điểm yêu cầu,
 * thông tin chuyển khoản.
 */
@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "organizer_user_id", nullable = false)
    private String organizerUserId;

    @Column(name = "gross_amount")
    private Double grossAmount;

    @Column(name = "platform_fee")
    private Double platformFee;

    @Column(name = "net_amount")
    private Double netAmount;

    @Column(name = "requested_amount_vnd")
    private Long requestedAmountVnd;

    @Column(name = "request_type")
    private String requestType;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "quote_id")
    private String quoteId;

    @Column(name = "account_version")
    private Long accountVersion;

    @Column(name = "advance_rate_snapshot") private Integer advanceRateSnapshot;
    @Column(name = "policy_version_snapshot") private Long policyVersionSnapshot;
    @Column(name = "policy_scope_snapshot") private String policyScopeSnapshot;
    @Column(name = "available_amount_snapshot") private Long availableAmountSnapshot;
    @Column(name = "advance_base_snapshot") private Long advanceBaseSnapshot;
    @Column(name = "advance_limit_snapshot") private Long advanceLimitSnapshot;
    @Column(name = "paid_amount_snapshot") private Long paidAmountSnapshot;
    @Column(name = "reserved_amount_snapshot") private Long reservedAmountSnapshot;

    @Column(name = "account_holder_snapshot")
    private String accountHolderSnapshot;

    @Column(name = "account_number_snapshot")
    private String accountNumberSnapshot;

    @Column(name = "bank_name_snapshot")
    private String bankNameSnapshot;

    @Column(name = "transfer_reference")
    private String transferReference;

    @Column(name = "transfer_receipt_url")
    private String transferReceiptUrl;

    @Column(name = "public_reason", columnDefinition = "TEXT")
    private String publicReason;

    @Enumerated(EnumType.STRING)
    private PayoutStatus status;

    @Column(name = "transferred_at")
    private LocalDateTime transferredAt;

    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "processing_at") private LocalDateTime processingAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu: gán thời điểm tạo, cập nhật và trạng thái {@code PENDING} nếu chưa có. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = PayoutStatus.PENDING;
    }

    /** Trước khi cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
