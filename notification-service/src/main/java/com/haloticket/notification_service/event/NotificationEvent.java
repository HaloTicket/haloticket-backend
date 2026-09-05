package com.haloticket.notification_service.event;

import com.haloticket.notification_service.enums.NotificationChannel;
import java.time.Instant;
import java.util.Map;

public record NotificationEvent(String eventId, String templateId, String userId,
                                String recipientEmail, NotificationChannel channel,
                                Map<String, String> variables, Instant expiresAt) {
}
