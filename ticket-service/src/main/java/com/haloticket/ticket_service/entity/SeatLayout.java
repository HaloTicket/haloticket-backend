package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "seat_layouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLayout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "svg_data", columnDefinition = "TEXT")
    private String svgData;

    @Enumerated(EnumType.STRING)
    private SeatLayoutStatus status;

    @OneToMany(mappedBy = "seatLayout", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SeatZone> seatZones;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = SeatLayoutStatus.DRAFT;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
