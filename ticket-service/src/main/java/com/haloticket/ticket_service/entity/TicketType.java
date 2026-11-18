package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.TicketTypeStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Loại vé của một sự kiện hoặc suất diễn (bảng {@code ticket_types}): giá, số lượng, giới hạn mua mỗi đơn và trạng thái bán. */
@Entity
@Table(name = "ticket_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketType {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "showtime_id")
    private String showtimeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double price;

    @Column(name = "is_free")
    private Boolean isFree;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "initial_quantity")
    private Integer initialQuantity;

    // Số lượng đang bị các booking vé không chỗ ngồi chưa thanh toán giữ. Còn lại = totalQuantity - reservedQuantity.
    @Column(name = "reserved_quantity")
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Column(name = "min_per_order")
    private Integer minPerOrder;

    @Column(name = "max_per_order")
    private Integer maxPerOrder;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private TicketTypeStatus ticketTypeStatus;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    /** Khi lưu: ghi thời điểm tạo và cập nhật, mặc định trạng thái {@code ACTIVE}. */
    @PrePersist 
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.ticketTypeStatus == null) this.ticketTypeStatus = TicketTypeStatus.ACTIVE;
    }

    /** Khi cập nhật: ghi lại thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
