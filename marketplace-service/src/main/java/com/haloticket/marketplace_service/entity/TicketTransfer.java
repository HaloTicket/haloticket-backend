package com.haloticket.marketplace_service.entity;

import com.haloticket.marketplace_service.enums.TransferStatus;
import com.haloticket.marketplace_service.enums.TransferType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "ticket_transfers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketTransfer {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "marketplace_transaction_id", unique = true) private MarketplaceTransaction transaction;
    @Column(name = "ticket_id", nullable = false) private String ticketId;
    @Column(name = "event_id") private String eventId;
    @Column(name = "ticket_type_name") private String ticketTypeName;
    @Column(name = "from_user_id", nullable = false) private String fromUserId;
    @Column(name = "to_user_id") private String toUserId;
    @Column(name = "recipient_email") private String recipientEmail;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TransferType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TransferStatus status;
    @Column(name = "invitation_token_hash") private String invitationTokenHash;
    @Column(name = "invitation_expires_at") private Instant invitationExpiresAt;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(name = "cancellation_reason") private String cancellationReason;
    @Column(name = "saga_id", nullable = false, unique = true) private String sagaId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "transferred_at") private Instant transferredAt;
    @Version private Long version;
    @PrePersist void onCreate() { createdAt = Instant.now(); if (status == null) status = TransferStatus.PENDING; }
}
