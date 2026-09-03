package com.haloticket.notification_service.dto.request;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import lombok.Data;

@Data
public class NotificationTemplateCreateRequest {
    private NotificationType type;
    private NotificationChannel channel;
    private String subjectTemplate;
    private String bodyTemplate;
    private NotificationTemplateStatus status;
}
