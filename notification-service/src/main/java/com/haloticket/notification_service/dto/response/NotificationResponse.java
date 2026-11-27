package com.haloticket.notification_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.haloticket.notification_service.enums.AnnouncementCategory;
import com.haloticket.notification_service.enums.NotificationSource;
import com.haloticket.notification_service.enums.NotificationVisibility;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationPriority;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Thông tin một thông báo trả về cho client, gồm nội dung hiển thị, trạng thái đọc, trạng thái gửi và mốc thời gian. */
@Data
@Builder
public class NotificationResponse {
    private String id;
    private String eventId;
    private NotificationSource source;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventTitle;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AnnouncementCategory category;
    private NotificationVisibility visibility;
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
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
