package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "banner_url", columnDefinition = "TEXT")
    private String bannerUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "location_type")
    private String locationType;

    @Column(name = "location_name", columnDefinition = "TEXT")
    private String locationName;

    private String city;

    private String ward;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String author;

    private String director;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "event_cast", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "actor_name")
    private List<String> cast;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "event_gallery", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "image_url", columnDefinition = "TEXT")
    private List<String> galleryImages;

    @Column(name = "dates_summary")
    private String datesSummary;

    @Column(name = "extra_days_count")
    private Integer extraDaysCount;

    @Column(name = "min_price")
    private Double minPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_status")
    private EventStatus eventStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_approval_status")
    private EventApprovalStatus eventApprovalStatus;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "sale_start_time")
    private LocalDateTime saleStartTime;

    @Column(name = "sale_end_time")
    private LocalDateTime saleEndTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private EventCategory category;

    @ManyToMany
    @JoinTable(
            name = "event_organizer_mapping",
            joinColumns = @JoinColumn(name = "event_id"),
            inverseJoinColumns = @JoinColumn(name = "organizer_id")
    )
    private List<EventOrganizer> organizers;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StaffAssignment> staffAssignments;

    @Column(name = "is_private")
    private Boolean isPrivate;

    @Column(name = "confirmation_message", columnDefinition = "TEXT")
    private String confirmationMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.eventStatus == null) this.eventStatus = EventStatus.PENDING;
        if (this.eventApprovalStatus == null) this.eventApprovalStatus = EventApprovalStatus.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
