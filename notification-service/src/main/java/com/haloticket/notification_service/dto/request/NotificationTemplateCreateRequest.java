package com.haloticket.notification_service.dto.request;

import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import lombok.Data;

/** Yêu cầu tạo mẫu thông báo: loại, kênh, mẫu tiêu đề, mẫu nội dung (chứa {@code {{biến}}}) và trạng thái (mặc định {@code ACTIVE}). */
@Data
public class NotificationTemplateCreateRequest {
    private NotificationType type;
    private NotificationChannel channel;
    private String subjectTemplate;
    private String bodyTemplate;
    private NotificationTemplateStatus status;
}
