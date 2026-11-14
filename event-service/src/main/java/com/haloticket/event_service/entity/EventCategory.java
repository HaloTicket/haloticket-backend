package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.CategoryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /** name of an icon the apps know how to draw; empty means the default one */
    private String icon;

    /** position in the category lists, smallest first; null counts as the end */
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

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = CategoryStatus.ACTIVE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
