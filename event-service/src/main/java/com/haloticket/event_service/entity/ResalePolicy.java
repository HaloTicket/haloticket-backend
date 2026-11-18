package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** Thực thể chính sách bán lại vé của sự kiện (bảng {@code resale_policies}), mỗi sự kiện tối đa một bản: biên độ giá, phí dịch vụ, thời gian mở và phạm vi loại vé. Có {@code version} để khóa lạc quan. */
@Entity
@Table(name = "resale_policies", uniqueConstraints = @UniqueConstraint(name = "uk_resale_policy_event", columnNames = "event_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResalePolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "event_id", nullable = false, unique = true) private String eventId;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "min_price_percent", precision = 7, scale = 3, nullable = false) private BigDecimal minPricePercent;
    @Column(name = "max_price_percent", precision = 7, scale = 3, nullable = false) private BigDecimal maxPricePercent;
    @Builder.Default
    @Column(name = "service_fee_percent", precision = 7, scale = 3, nullable = false) private BigDecimal serviceFeePercent = new BigDecimal("5");
    @Column(name = "require_approval", nullable = false) private boolean requireApproval;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "opens_at") private Instant opensAt;
    @Column(name = "ticket_type_scope", nullable = false) private String ticketTypeScope = "ALL";
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "resale_policy_ticket_types", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "ticket_type_id") private Set<String> allowedTicketTypeIds = Set.of();
    @Column(name = "terms_version") private String termsVersion;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** Trước khi lưu lần đầu: đặt thời điểm tạo và cập nhật. */
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
