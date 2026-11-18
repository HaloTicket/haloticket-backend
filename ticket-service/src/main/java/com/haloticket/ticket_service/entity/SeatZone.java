package com.haloticket.ticket_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

/** Một khu vực ghế trên sơ đồ (bảng {@code seat_zones}): hình dạng, vị trí, màu sắc, sức chứa và loại vé gắn với khu vực. */
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

    @Column(name = "ticket_type_id")
    private String ticketTypeId;

    @Column(name = "shape_type")
    private String shapeType; // hình dạng: RECTANGLE, CIRCLE, POLYGON, PATH_CURVED

    @Column(name = "color_code")
    private String colorCode;

    @Column(name = "fill_color")
    private String fillColor;

    @Column(name = "stroke_color")
    private String strokeColor;

    @Column(name = "curvature")
    private Double curvature;

    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private Double rotation;

    @Column(name = "points", columnDefinition = "TEXT")
    private String points;

    private Integer capacity;

    @OneToMany(mappedBy = "seatZone", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SeatRow> seatRows;
}
