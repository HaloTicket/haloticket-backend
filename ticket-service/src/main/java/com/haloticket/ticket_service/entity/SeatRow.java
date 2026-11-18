package com.haloticket.ticket_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

/** Một hàng ghế thuộc một khu vực (bảng {@code seat_rows}), chứa danh sách ghế. */
@Entity
@Table(name = "seat_rows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatRow {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_zone_id", nullable = false)
    private SeatZone seatZone;

    @Column(nullable = false)
    private String label;

    @OneToMany(mappedBy = "seatRow", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Seat> seats;
}
