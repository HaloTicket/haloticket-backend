package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "marketplace_transactions", indexes = @Index(name = "idx_market_tx_listing_status", columnList = "ticket_listing_id,status"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MarketplaceTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "ticket_listing_id", nullable = false) private TicketListing ticketListing;
    @Column(name = "buyer_id", nullable = false) private String buyerId;
    @Column(nullable = false, precision = 19, scale = 0) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MarketplaceTransactionStatus status;
    @Column(name = "payment_id") private String paymentId;
    @Column(name = "reservation_expires_at") private Instant reservationExpiresAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "saga_id") private String sagaId;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; if (status == null) status = MarketplaceTransactionStatus.PENDING; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
