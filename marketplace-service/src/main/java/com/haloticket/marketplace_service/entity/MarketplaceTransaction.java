package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Giao dịch mua vé bán lại: gắn với một tin bán, người mua, người bán, số tiền, thông tin thanh toán, hạn giữ chỗ
 * và danh sách vé đã chọn. Dùng khóa phiên bản ({@code @Version}) để tránh ghi đè đồng thời.
 */
@Entity @Table(name = "marketplace_transactions", indexes = @Index(name = "idx_market_tx_listing_status", columnList = "ticket_listing_id,status"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MarketplaceTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "ticket_listing_id", nullable = false) private TicketListing ticketListing;
    @Column(name = "buyer_id", nullable = false) private String buyerId;
    @Column(name = "seller_id", nullable = false) private String sellerId;
    @Column(nullable = false, precision = 19, scale = 0) private BigDecimal amount;
    @Column(name = "service_fee_percent", precision = 7, scale = 3) @Builder.Default private BigDecimal serviceFeePercent = new BigDecimal("5.00");
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "VND";
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MarketplaceTransactionStatus status;
    @Column(name = "payment_id") private String paymentId;
    @Column(name = "payment_reference", unique = true) private String paymentReference;
    @Column(name = "reservation_expires_at") private Instant reservationExpiresAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "saga_id") private String sagaId;
    @Column(name = "selection_explicit") private Boolean selectionExplicit;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "marketplace_transaction_tickets", joinColumns = @JoinColumn(name = "transaction_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_transaction_ticket", columnNames = {"transaction_id", "ticket_id"}))
    @Column(name = "ticket_id", nullable = false)
    @Builder.Default private Set<String> ticketIds = new LinkedHashSet<>();
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** Đặt thời điểm tạo, cập nhật và trạng thái mặc định {@code PENDING_PAYMENT} nếu chưa có, trước khi lưu mới. */
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; if (status == null) status = MarketplaceTransactionStatus.PENDING_PAYMENT; }
    /** Cập nhật thời điểm sửa đổi trước mỗi lần lưu thay đổi. */
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
