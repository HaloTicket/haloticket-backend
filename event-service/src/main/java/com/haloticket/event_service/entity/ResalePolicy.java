package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "resale_policies", uniqueConstraints = @UniqueConstraint(name = "uk_resale_policy_event", columnNames = "event_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResalePolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "event_id", nullable = false, unique = true) private String eventId;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "min_price_percent", precision = 7, scale = 3, nullable = false) private BigDecimal minPricePercent;
    @Column(name = "max_price_percent", precision = 7, scale = 3, nullable = false) private BigDecimal maxPricePercent;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "terms_version") private String termsVersion;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
