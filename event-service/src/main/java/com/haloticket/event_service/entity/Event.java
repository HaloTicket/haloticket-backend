package com.haloticket.event_service.entity;

import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.TicketSyncStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/** Thực thể sự kiện (bảng {@code events}): nội dung, địa điểm, thời gian diễn và mở bán, trạng thái duyệt, nhà tổ chức, nhân sự, suất diễn và bản chụp vé đã đồng bộ. */
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

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EventShowtime> showtimes;

    @Column(name = "is_private")
    private Boolean isPrivate;

    @Column(name = "confirmation_message", columnDefinition = "TEXT")
    private String confirmationMessage;

    @Column(name = "enable_custom_questions")
    private Boolean enableCustomQuestions;

    @Column(name = "question_scope")
    private String questionScope;

    @Column(name = "tickets_data", columnDefinition = "TEXT")
    private String ticketsData;

    @Enumerated(EnumType.STRING)
    @Column(name = "tickets_sync_status", length = 16)
    private TicketSyncStatus ticketsSyncStatus;

    @Column(name = "tickets_sync_error", length = 64)
    private String ticketsSyncError;

    @Column(name = "tickets_synced_at")
    private LocalDateTime ticketsSyncedAt;

    @Column(name = "custom_questions_data", columnDefinition = "TEXT")
    private String customQuestionsData;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "rejection_notes", columnDefinition = "TEXT")
    private String rejectionNotes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Trước khi lưu lần đầu: đặt thời điểm tạo và cập nhật; trạng thái sự kiện và trạng thái duyệt mặc định là {@code PENDING}. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.eventStatus == null) this.eventStatus = EventStatus.PENDING;
        if (this.eventApprovalStatus == null) this.eventApprovalStatus = EventApprovalStatus.PENDING;
    }

    /** Trước mỗi lần cập nhật: làm mới thời điểm cập nhật. */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
