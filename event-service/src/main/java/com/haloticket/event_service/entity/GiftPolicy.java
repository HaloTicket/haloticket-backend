package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Set;

@Entity @Table(name = "gift_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GiftPolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "event_id", nullable = false, unique = true) private String eventId;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "opens_at") private Instant opensAt;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "invitation_ttl_hours", nullable = false) private int invitationTtlHours;
    @Column(name = "ticket_type_scope", nullable = false) private String ticketTypeScope;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "gift_policy_ticket_types", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "ticket_type_id") private Set<String> allowedTicketTypeIds;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
