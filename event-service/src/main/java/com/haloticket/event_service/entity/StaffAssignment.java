package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

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
    private String userId; // Link sang User Service

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

    /** SHA-256 (hex) of the one-time invitation token; the token itself is never stored. Cleared once it is used. */
    @Column(name = "invitation_token_hash")
    private String invitationTokenHash;

    @Column(name = "invitation_expires_at")
    private LocalDateTime invitationExpiresAt;

    /** user-service id of the person who sent the invitation. */
    @Column(name = "invited_by")
    private String invitedBy;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "invitation_resend_count")
    private Integer invitationResendCount;

    public String statusLabel() {
        if (status == StaffAssignmentStatus.PENDING && invitationExpiresAt != null && invitationExpiresAt.isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return status == null ? null : status.name();
    }

    @PrePersist
    protected void onCreate() {
        if (this.assignedAt == null) this.assignedAt = LocalDateTime.now();
        if (this.status == null) this.status = StaffAssignmentStatus.ACTIVE;
    }
}
