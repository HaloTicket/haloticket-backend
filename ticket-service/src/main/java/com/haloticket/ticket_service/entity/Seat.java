package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.enums.SeatType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_row_id", nullable = false)
    private SeatRow seatRow;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "x_coordinate")
    private Double xCoordinate;

    @Column(name = "y_coordinate")
    private Double yCoordinate;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type")
    private SeatType seatType;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_status")
    private SeatStatus seatStatus;

    @OneToOne(mappedBy = "seat", fetch = FetchType.LAZY)
    private Ticket ticket;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (this.seatStatus == null) this.seatStatus = SeatStatus.AVAILABLE;
        if (this.seatType == null) this.seatType = SeatType.REGULAR;
    }
}
