package com.haloticket.booking_service.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Ghế đã giữ hoặc bán cho một đơn (bảng {@code seat_reservations}); dùng khóa lạc quan {@code @Version} để tránh hai đơn tranh cùng một ghế. */
@Entity
@Table(name = "seat_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    @JsonIgnore
    private Booking booking;

    @Column(name = "seat_id", nullable = false)
    private String seatId;

    @Column(name = "showtime_id")
    private String showtimeId;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    private SeatReservationStatus status;

    @Column(name = "lock_expires_at")
    private LocalDateTime lockExpiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Trước khi lưu lần đầu: gán thời gian tạo và đặt trạng thái mặc định {@code HOLDING}. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = SeatReservationStatus.HOLDING;
    }
}
