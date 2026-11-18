package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Thực thể địa điểm tổ chức sự kiện (bảng {@code venues}): vị trí, sức chứa, loại, trạng thái xác minh và mẫu sơ đồ chỗ ngồi. */
@Entity
@Table(name = "venues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "ward")
    private String ward;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "type")
    private String type; // loại địa điểm: "indoor", "outdoor", "hybrid"

    @Column(name = "verified")
    private Boolean verified;

    @Column(name = "events_hosted")
    private Integer eventsHosted;

    @Column(name = "is_public")
    private Boolean isPublic;

    @Column(name = "organizer_id")
    private String organizerId;

    @Column(name = "seatmap_template", columnDefinition = "TEXT")
    private String seatmapTemplate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu lần đầu: điền giá trị mặc định cho trường còn trống (chưa xác minh, công khai, 0 sự kiện đã tổ chức, sức chứa 1000, loại {@code indoor}). */
    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (verified == null) verified = false;
        if (isPublic == null) isPublic = true;
        if (eventsHosted == null) eventsHosted = 0;
        if (capacity == null) capacity = 1000;
        if (type == null) type = "indoor";
    }

    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
