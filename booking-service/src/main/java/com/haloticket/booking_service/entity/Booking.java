package com.haloticket.booking_service.entity;

import com.haloticket.booking_service.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/** Đơn đặt vé (bảng {@code bookings}): người mua, sự kiện, suất chiếu, voucher, số tiền, trạng thái, các dòng vé và ghế giữ. */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "booking_code", unique = true, nullable = false)
    private String bookingCode;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "showtime_id")
    private String showtimeId;

    @Column(name = "voucher_id")
    private String voucherId;

    private Double subtotal;

    @Column(name = "discount_amount")
    private Double discountAmount;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    // true khi tồn kho vé vào cổng tự do đã được giữ cho đơn này lúc tạo đơn.
    @Column(name = "ga_reserved")
    private Boolean gaReserved;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BookingItem> items;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SeatReservation> seatReservations;

    // thời điểm yêu cầu phát hành lại vé gần nhất và số lần đã yêu cầu (nút của khách, nút của admin hoặc bộ lập lịch)
    @Column(name = "last_reissue_at")
    private LocalDateTime lastReissueAt;

    @Column(name = "reissue_attempts")
    private Integer reissueAttempts;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu lần đầu: gán thời gian tạo, cập nhật và đặt trạng thái mặc định {@code PENDING}. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = BookingStatus.PENDING;
    }

    /** Trước mỗi lần cập nhật: làm mới thời gian cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
