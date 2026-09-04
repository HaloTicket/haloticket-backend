package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationEmailSender emailSender;

    @KafkaListener(topics = "${notification.kafka.topic:haloticket.notifications.v1}",
            groupId = "${notification.kafka.group:notification-service}")
    public void consume(String payload) throws Exception {
        NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);
        validate(event);
        NotificationResponse notification = notificationService.processEvent(event);
        if (notification.getStatus() == NotificationStatus.SENT) return;
        NotificationTemplate template = templateRepository.findById(event.templateId())
                .orElseThrow(() -> new IllegalArgumentException("Notification template not found"));
        emailSender.send(template, event.recipientEmail(), event.variables());
        notificationService.markAsSent(notification.getId());
    }

    private void validate(NotificationEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.templateId() == null || event.templateId().isBlank()
                || event.recipientEmail() == null || event.recipientEmail().isBlank()
                || event.channel() != NotificationChannel.EMAIL
                || (event.expiresAt() != null && event.expiresAt().isBefore(Instant.now()))) {
            throw new IllegalArgumentException("Invalid notification event");
        }
    }
}
