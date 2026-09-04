package com.haloticket.payment_service.entity;

import com.haloticket.payment_service.enums.PayoutStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "organizer_user_id", nullable = false)
    private String organizerUserId;

    @Column(name = "gross_amount")
    private Double grossAmount;

    @Column(name = "platform_fee")
    private Double platformFee;

    @Column(name = "net_amount")
    private Double netAmount;

    @Enumerated(EnumType.STRING)
    private PayoutStatus status;

    @Column(name = "transferred_at")
    private LocalDateTime transferredAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = PayoutStatus.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
