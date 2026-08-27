package com.haloticket.ticket_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "seat_zones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatZone {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_layout_id", nullable = false)
    private SeatLayout seatLayout;

    @Column(nullable = false)
    private String name;

    @Column(name = "color_code")
    private String colorCode;

    private Integer capacity;

    @OneToMany(mappedBy = "seatZone", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SeatRow> seatRows;
}
