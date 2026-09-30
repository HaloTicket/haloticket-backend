package com.haloticket.marketplace_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "event_transfer_policy_state")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EventTransferPolicyState {
    @Id private String eventId;
    @Column(name = "resale_enabled", nullable = false) private boolean resaleEnabled;
    @Column(name = "resale_version") private Long resaleVersion;
    @Column(name = "gift_enabled", nullable = false) private boolean giftEnabled;
    @Column(name = "gift_version") private Long giftVersion;
    @Column(name = "resale_opens_at") private Instant resaleOpensAt;
    @Column(name = "resale_closes_at") private Instant resaleClosesAt;
    @Column(name = "gift_opens_at") private Instant giftOpensAt;
    @Column(name = "gift_closes_at") private Instant giftClosesAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
