package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.DiscountType;
import com.haloticket.event_service.enums.VoucherStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Thực thể voucher của sự kiện (bảng {@code vouchers}): mã giảm giá, mức giảm, giới hạn sử dụng, phạm vi áp dụng và thời gian hiệu lực. */
@Entity
@Table(name = "vouchers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, unique = true)
    private String code;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String terms;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type")
    private DiscountType discountType;

    @Column(name = "discount_value")
    private Double discountValue;

    @Column(name = "apply_ticket_limit_type")
    private String applyTicketLimitType;

    @Column(name = "ticket_limit_count")
    private Integer ticketLimitCount;

    @Column(name = "max_orders_per_buyer")
    private Integer maxOrdersPerBuyer;

    @Column(name = "min_tickets_per_order")
    private Integer minTicketsPerOrder;

    @Column(name = "max_tickets_per_order")
    private Integer maxTicketsPerOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_scope")
    private ApplyScope applyScope;

    @Column(name = "applicable_showtimes", columnDefinition = "TEXT")
    private String applicableShowtimes;

    @Column(name = "used_count")
    private Integer usedCount;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    private VoucherStatus status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu lần đầu: đặt thời điểm tạo; mặc định đã dùng 0 lần, trạng thái {@code ACTIVE}, phạm vi {@code ALL}. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.usedCount == null) this.usedCount = 0;
        if (this.status == null) this.status = VoucherStatus.ACTIVE;
        if (this.applyScope == null) this.applyScope = ApplyScope.ALL;
    }

    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
