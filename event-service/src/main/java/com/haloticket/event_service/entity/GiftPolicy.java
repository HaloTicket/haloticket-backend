package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Set;

/** Thực thể chính sách tặng vé của sự kiện (bảng {@code gift_policies}), mỗi sự kiện tối đa một bản. Có {@code version} để khóa lạc quan. */
@Entity @Table(name = "gift_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GiftPolicy {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "event_id", nullable = false, unique = true) private String eventId;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "opens_at") private Instant opensAt;
    @Column(name = "closes_at") private Instant closesAt;
    @Column(name = "invitation_ttl_hours", nullable = false) private int invitationTtlHours;
    @Builder.Default
    @Column(name = "require_ekyc", nullable = false) private boolean requireEkyc = true;
    @Builder.Default
    @Column(name = "max_gift_count", nullable = false) private int maxGiftCount = 3;
    @Column(name = "ticket_type_scope", nullable = false) private String ticketTypeScope;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "gift_policy_ticket_types", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "ticket_type_id") private Set<String> allowedTicketTypeIds;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    /** Trước khi lưu lần đầu: đặt thời điểm tạo và cập nhật. */
    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
