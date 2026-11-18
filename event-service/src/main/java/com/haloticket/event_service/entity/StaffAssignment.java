package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Thực thể phân công nhân sự cho sự kiện (bảng {@code staff_assignments}): người dùng, vai trò, trạng thái và thông tin lời mời (băm token, hạn dùng, số lần gửi lại). */
@Entity
@Table(name = "staff_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "user_id", nullable = false)
    private String userId; // liên kết sang user-service

    private String email;

    private String name;

    @Enumerated(EnumType.STRING)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    private StaffAssignmentStatus status;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Mã băm SHA-256 (hex) của token lời mời dùng một lần; bản thân token không bao giờ được lưu. Được xóa sau khi token đã dùng. */
    @Column(name = "invitation_token_hash")
    private String invitationTokenHash;

    @Column(name = "invitation_expires_at")
    private LocalDateTime invitationExpiresAt;

    /** Mã người dùng (trong user-service) của người đã gửi lời mời. */
    @Column(name = "invited_by")
    private String invitedBy;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "invitation_resend_count")
    private Integer invitationResendCount;

    /**
     * Nhãn trạng thái để hiển thị: lời mời {@code PENDING} đã quá hạn thì trả {@code EXPIRED}, ngược lại trả tên trạng thái.
     * @return nhãn trạng thái, hoặc {@code null} nếu chưa có trạng thái
     */
    public String statusLabel() {
        if (status == StaffAssignmentStatus.PENDING && invitationExpiresAt != null && invitationExpiresAt.isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return status == null ? null : status.name();
    }

    /** Trước khi lưu lần đầu: thời điểm phân công mặc định là hiện tại, trạng thái mặc định là {@code ACTIVE}. */
    @PrePersist
    protected void onCreate() {
        if (this.assignedAt == null) this.assignedAt = LocalDateTime.now();
        if (this.status == null) this.status = StaffAssignmentStatus.ACTIVE;
    }
}
