package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Khoản đối soát trả tiền cho người bán từ một giao dịch bán lại: tiền gộp, phí nền tảng, tiền thực nhận
 * và thời điểm đủ điều kiện thanh toán. Mỗi giao dịch có tối đa một khoản đối soát.
 */
@Entity
@Table(name = "seller_settlements", uniqueConstraints =
        @UniqueConstraint(name = "uk_settlement_marketplace_transaction", columnNames = "marketplace_transaction_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellerSettlement {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "marketplace_transaction_id", nullable = false)
    private MarketplaceTransaction marketplaceTransaction;
    @Column(name = "seller_id", nullable = false) private String sellerId;
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 0) private BigDecimal grossAmount;
    @Column(name = "platform_fee", nullable = false, precision = 19, scale = 0) private BigDecimal platformFee;
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 0) private BigDecimal netAmount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "VND";
    @Enumerated(EnumType.STRING) @Column(nullable = false) @Builder.Default
    private SettlementStatus status = SettlementStatus.PENDING_SETTLEMENT;
    @Column(name = "eligible_at", nullable = false) private Instant eligibleAt;
    @Column(name = "settled_at") private Instant settledAt;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** Đặt thời điểm tạo, cập nhật và mặc định {@code eligibleAt} bằng thời điểm tạo nếu chưa có. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (eligibleAt == null) eligibleAt = createdAt;
    }

    /** Cập nhật thời điểm sửa đổi trước mỗi lần lưu thay đổi. */
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
