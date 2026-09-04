package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.TicketTypeStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

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

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double price;

    @Column(name = "is_free")
    private Boolean isFree;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "min_per_order")
    private Integer minPerOrder;

    @Column(name = "max_per_order")
    private Integer maxPerOrder;

    @Column(name = "start_sale_date")
    private LocalDateTime startSaleDate;

    @Column(name = "end_sale_date")
    private LocalDateTime endSaleDate;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private TicketTypeStatus ticketTypeStatus;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist 
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.ticketTypeStatus == null) this.ticketTypeStatus = TicketTypeStatus.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
