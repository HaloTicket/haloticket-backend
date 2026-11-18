package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.CheckInStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Bản ghi vé đã check-in thành công (bảng {@code checkins}); mỗi vé chỉ có một bản ghi. */
@Entity
@Table(name = "checkins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Checkin {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, unique = true)
    private Ticket ticket;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "staff_assignment_id")
    private String staffAssignmentId;

    @Enumerated(EnumType.STRING)
    private CheckInStatus status;

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    /** Tự đặt thời điểm check-in là lúc lưu nếu chưa có. */
    @PrePersist
    protected void onCreate() {
        if (this.checkedInAt == null) this.checkedInAt = LocalDateTime.now();
    }
}
