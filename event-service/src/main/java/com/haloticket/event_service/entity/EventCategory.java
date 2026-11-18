package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.CategoryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Thực thể danh mục sự kiện (bảng {@code event_categories}). Danh mục chỉ bị tắt chứ không bị xóa để các sự kiện cũ vẫn trỏ tới được. */
@Entity
@Table(name = "event_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;
    
    private String slug;
    
    private String imageUrl;

    /** Tên biểu tượng mà ứng dụng biết cách vẽ; để trống thì dùng biểu tượng mặc định. */
    private String icon;

    /** Vị trí trong danh sách danh mục, số nhỏ xếp trước; {@code null} được tính là cuối danh sách. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    private CategoryStatus status;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Event> events;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu lần đầu: đặt thời điểm tạo và cập nhật; trạng thái mặc định là {@code ACTIVE}. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = CategoryStatus.ACTIVE;
    }

    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
