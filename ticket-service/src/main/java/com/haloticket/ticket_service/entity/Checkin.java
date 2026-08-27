package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.CheckInStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

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

    @PrePersist
    protected void onCreate() {
        if (this.checkedInAt == null) this.checkedInAt = LocalDateTime.now();
    }
}
