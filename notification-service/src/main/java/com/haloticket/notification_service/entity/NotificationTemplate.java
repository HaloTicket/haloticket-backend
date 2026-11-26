package com.haloticket.notification_service.entity;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;

@Document("notification_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationTemplate {
    @MongoId(FieldType.STRING)
    private String id;
    private NotificationType type;
    private NotificationChannel channel;
    private String subjectTemplate;
    private String bodyTemplate;
    private NotificationTemplateStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
