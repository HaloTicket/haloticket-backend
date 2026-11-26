package com.haloticket.notification_service.entity;

import com.haloticket.notification_service.dto.request.AnnouncementRequest;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import com.haloticket.notification_service.dto.response.AnnouncementScan;
import com.haloticket.notification_service.enums.AnnouncementCategory;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;
import java.util.List;

@Document("notification_announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAnnouncement {
    @MongoId(FieldType.STRING)
    private String id;
    private String actorId;
    private String deliveryActorId;
    private String requestId;
    private AnnouncementRequest payload;
    private Status status;
    private Instant createdAt;
    private Instant availableAt;
    private String leaseToken;
    private Instant leaseUntil;
    private String snapshotId;
    private long recipientCount;
    private int attempts;
    private String errorCode;
    private String organizerEventId;
    private String eventTitle;
    private AnnouncementCategory category;
    private AnnouncementTarget target;
    private Long previewRecipientCount;
    private boolean recipientCountConfirmed;
    private String initialContentHash;
    private int revision;
    private Instant submittedAt;
    private Instant reviewedAt;
    private String reviewReason;
    private AnnouncementScan scan;
    private List<ReviewEntry> history;

    public AnnouncementTarget effectiveTarget() {
        return target == null ? AnnouncementTarget.CUSTOMER_ALL : target;
    }

    public String effectiveDeliveryActorId() {
        return deliveryActorId == null ? actorId : deliveryActorId;
    }

    public record ReviewEntry(int revision, Status status, String actorId, Instant at, String reason) {}

    public enum Status {
        QUEUED, PROCESSING, COMPLETED, FAILED, PENDING_REVIEW, CHANGES_REQUESTED, REJECTED
    }
}
