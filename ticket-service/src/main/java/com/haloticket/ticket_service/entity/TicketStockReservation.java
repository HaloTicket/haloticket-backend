package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.StockReservationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Bản ghi giữ chỗ tồn kho của một booking cho một loại vé (bảng {@code ticket_stock_reservations}); mỗi cặp booking và loại vé chỉ có một bản ghi. */
@Entity
@Table(name = "ticket_stock_reservations",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_reservation_booking_type", columnNames = {"booking_id", "ticket_type_id"}),
        indexes = @Index(name = "idx_stock_reservation_status_expires", columnList = "status, expires_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketStockReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "booking_id", nullable = false)
    private String bookingId;

    @Column(name = "ticket_type_id", nullable = false)
    private String ticketTypeId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StockReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Khi lưu: ghi thời điểm tạo và cập nhật. */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Khi cập nhật: ghi lại thời điểm cập nhật. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
