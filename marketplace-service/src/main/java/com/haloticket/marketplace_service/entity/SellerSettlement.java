package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

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

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (eligibleAt == null) eligibleAt = createdAt;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
