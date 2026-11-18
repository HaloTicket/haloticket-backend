package com.haloticket.notification_service.dto.request;

import com.haloticket.notification_service.enums.NotificationChannel;
import lombok.Data;

import java.util.Map;

/** Yêu cầu tạo thông báo từ một mẫu: mã mẫu, người nhận, biến thay thế và kênh gửi (bỏ trống thì dùng kênh của mẫu). */
@Data
public class NotificationCreateRequest {
    private String templateId;
    private String userId;
    private Map<String, String> variables;
    private NotificationChannel channel;
}
