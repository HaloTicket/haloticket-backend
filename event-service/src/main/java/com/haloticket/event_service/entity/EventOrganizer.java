package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.EventOrganizerRole;
import com.haloticket.event_service.enums.EventOrganizerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/** Thực thể hồ sơ nhà tổ chức (bảng {@code event_organizers}), gắn với một người dùng của user-service và liên kết nhiều-nhiều với sự kiện. */
@Entity
@Table(name = "event_organizers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventOrganizer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String name;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private EventOrganizerRole role;

    @Enumerated(EnumType.STRING)
    private EventOrganizerStatus status;

    @ManyToMany(mappedBy = "organizers")
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
        if (this.status == null) this.status = EventOrganizerStatus.ACTIVE;
    }

    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
