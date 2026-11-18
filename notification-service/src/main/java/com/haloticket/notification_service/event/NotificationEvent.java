package com.haloticket.notification_service.event;

import com.haloticket.notification_service.enums.NotificationChannel;
import java.time.Instant;
import java.util.Map;

/**
 * Sự kiện thông báo nhận từ Kafka: định danh sự kiện (chống xử lý trùng), mẫu, người nhận, kênh, biến thay thế
 * và thời điểm hết hạn (null nghĩa là không hết hạn).
 */
public record NotificationEvent(String eventId, String templateId, String userId,
                                String recipientEmail, NotificationChannel channel,
                                Map<String, String> variables, Instant expiresAt) {
}
