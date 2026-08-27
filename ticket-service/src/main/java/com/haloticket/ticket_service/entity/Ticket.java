package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_type_id", nullable = false)
    private TicketType ticketType;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", unique = true)
    private Seat seat;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "qr_code_hash", unique = true)
    private String qrCodeHash;

    @Column(name = "qr_version")
    private Integer qrVersion;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = TicketStatus.VALID;
        if (this.qrVersion == null) this.qrVersion = 1;
    }
}
