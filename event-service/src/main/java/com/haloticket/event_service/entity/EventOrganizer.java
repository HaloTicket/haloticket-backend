package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.EventOrganizerRole;
import com.haloticket.event_service.enums.EventOrganizerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

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

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = EventOrganizerStatus.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
