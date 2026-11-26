package com.haloticket.notification_service.entity;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationPriority;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.AnnouncementCategory;
import com.haloticket.notification_service.enums.NotificationSource;
import com.haloticket.notification_service.enums.NotificationVisibility;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;

@Document("notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    @MongoId(FieldType.STRING)
    private String id;
    private String eventId;
    private String organizerEventId;
    private NotificationSource source;
    private String eventTitle;
    private AnnouncementCategory category;
    private NotificationVisibility visibility;
    @Field(write = Field.Write.ALWAYS)
    private String templateId;
    private String userId;
    private String recipientEmail;
    private String variablesJson;
    private String title;
    private String message;
    private String type;
    private NotificationPriority priority;
    private Boolean readStatus;
    private NotificationChannel channel;
    private NotificationStatus status;
    private Instant createdAt;
    private Instant sentAt;
    private Instant deletedAt;
}
