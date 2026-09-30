package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.ListingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ticket_listings", indexes = {
        @Index(name = "idx_listing_status_created", columnList = "status, created_at"),
        @Index(name = "idx_listing_ticket", columnList = "ticket_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketListing {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "ticket_id", nullable = false) private String ticketId;
    @Column(name = "seller_id", nullable = false) private String sellerId;
    @Column(nullable = false, precision = 19, scale = 0) private BigDecimal price;
    @Column(name = "original_price", precision = 19, scale = 0) private BigDecimal originalPrice;
    @Column(name = "min_price_snapshot", precision = 19, scale = 0) private BigDecimal minPriceSnapshot;
    @Column(name = "max_price_snapshot", precision = 19, scale = 0) private BigDecimal maxPriceSnapshot;
    @Column(name = "event_id") private String eventId;
    @Column(name = "ticket_type_name_snapshot") private String ticketTypeNameSnapshot;
    @Column(name = "section_snapshot") private String sectionSnapshot;
    @Column(name = "row_snapshot") private String rowSnapshot;
    @Column(name = "seat_snapshot") private String seatSnapshot;
    @Column(nullable = false, columnDefinition = "integer default 1") @Builder.Default private Integer quantity = 1;
    @Column(name = "allow_retail", nullable = false, columnDefinition = "boolean default true") @Builder.Default private Boolean allowRetail = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ListingStatus status;
    @Column(name = "reserved_by") private String reservedBy;
    @Column(name = "reserved_until") private Instant reservedUntil;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "sold_at") private Instant soldAt;
    @Column(name = "policy_version") private Long policyVersion;
    @Column(name = "terms_version") private String termsVersion;
    @Column(name = "saga_id", nullable = false, unique = true) private String sagaId;
    @Column(name = "pause_reason") private String pauseReason;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @OneToMany(mappedBy = "ticketListing", fetch = FetchType.LAZY) @Builder.Default
    private List<MarketplaceTransaction> transactions = new ArrayList<>();
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ticket_listing_members", joinColumns = @JoinColumn(name = "listing_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_listing_member", columnNames = {"listing_id", "ticket_id"}))
    @Builder.Default private List<TicketMemberSnapshot> members = new ArrayList<>();

    public List<TicketMemberSnapshot> remainingMembers() {
        if (members == null || members.isEmpty()) return List.of(new TicketMemberSnapshot(ticketId, null, null,
                ticketTypeNameSnapshot, sectionSnapshot, rowSnapshot, seatSnapshot, originalPrice, false));
        return members.stream().filter(member -> !member.isSold()).toList();
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; if (status == null) status = ListingStatus.ACTIVE; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
