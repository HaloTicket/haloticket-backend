package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Thực thể nhật ký lượt xem sự kiện (bảng {@code event_view_logs}), dùng để tính lượt xem và người xem duy nhất cho phân tích. */
@Entity
@Table(name = "event_view_logs", indexes = {
    @Index(name = "idx_event_view_event_id", columnList = "event_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventViewLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "viewed_at", nullable = false)
    private LocalDateTime viewedAt;

    /** Trước khi lưu lần đầu: nếu chưa có thời điểm xem thì lấy thời điểm hiện tại. */
    @PrePersist
    protected void onCreate() {
        if (this.viewedAt == null) {
            this.viewedAt = LocalDateTime.now();
        }
    }
}
