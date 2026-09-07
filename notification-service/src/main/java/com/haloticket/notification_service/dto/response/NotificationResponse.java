package com.haloticket.notification_service.dto.response;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {
    private String id;
    private String eventId;
    private String templateId;
    private String userId;
    private String recipientEmail;
    private String variablesJson;
    private String title;
    private String message;
    private String type;
    private Boolean readStatus;
    private NotificationChannel channel;
    private NotificationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
