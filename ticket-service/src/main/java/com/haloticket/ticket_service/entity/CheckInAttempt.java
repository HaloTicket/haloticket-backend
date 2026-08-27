package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "checkin_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckInAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Column(name = "staff_assignment_id")
    private String staffAssignmentId;

    @Column(name = "device_info")
    private String deviceInfo;

    @Enumerated(EnumType.STRING)
    private CheckInAttemptResult result;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt;

    @PrePersist protected void onCreate() {
        if (this.attemptedAt == null) this.attemptedAt = LocalDateTime.now();
    }
}
