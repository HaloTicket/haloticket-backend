package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Vé đã phát hành cho một chủ sở hữu (bảng {@code tickets}): loại vé, ghế, giá, mã QR, trạng thái và các khóa chống phát hành trùng. */
@Entity
@Table(name = "tickets", indexes = {
        @Index(name = "idx_ticket_announcement_event_owner", columnList = "event_id, owner_id, status"),
        @Index(name = "idx_ticket_announcement_type", columnList = "event_id, ticket_type_id, owner_id"),
        @Index(name = "idx_ticket_announcement_showtime", columnList = "event_id, showtime_id, owner_id")
})
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

    @Column(name = "seat_reference")
    private String seatReference;

    @Column(name = "seat_section")
    private String seatSection;

    @Column(name = "seat_row_label")
    private String seatRowLabel;

    @Column(name = "seat_number_label")
    private String seatNumberLabel;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "showtime_id")
    private String showtimeId;

    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "issue_key", unique = true)
    private String issueKey;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "original_price", precision = 19, scale = 0)
    private java.math.BigDecimal originalPrice;

    @Column(name = "purchase_price", precision = 19, scale = 0)
    private java.math.BigDecimal purchasePrice;

    @Column(name = "acquisition_type", length = 30)
    private String acquisitionType;

    @Column(name = "gift_transfer_count", nullable = false)
    private int giftTransferCount;

    @Column(name = "lock_operation_id", unique = true)
    private String lockOperationId;

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


    /** Khi lưu: ghi thời điểm tạo, mặc định trạng thái {@code VALID} và phiên bản QR bằng 1. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = TicketStatus.VALID;
        if (this.qrVersion == null) this.qrVersion = 1;
    }
}
