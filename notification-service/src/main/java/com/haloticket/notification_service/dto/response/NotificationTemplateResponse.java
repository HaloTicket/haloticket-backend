package com.haloticket.notification_service.dto.response;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationTemplateResponse {
    private String id;
    private NotificationType type;
    private NotificationChannel channel;
    private String subjectTemplate;
    private String bodyTemplate;
    private NotificationTemplateStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
