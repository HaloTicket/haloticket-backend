package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Consumer Kafka nhận {@link NotificationEvent} (topic {@code notification.kafka.topic}) và gửi email thông báo. */
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationEmailSender emailSender;

    /**
     * Xử lý một sự kiện thông báo: kiểm tra hợp lệ, lưu thông báo (idempotent theo {@code eventId}),
     * nếu chưa gửi thì gửi email theo mẫu rồi đánh dấu {@code SENT}.
     *
     * @param payload JSON của {@link NotificationEvent}
     * @throws Exception khi JSON sai, sự kiện không hợp lệ, không có mẫu hoặc gửi email lỗi (để Kafka thử lại hoặc đưa vào DLT)
     */
    @KafkaListener(topics = "${notification.kafka.topic:haloticket.notifications.v1}",
            groupId = "${notification.kafka.group:notification-service}")
    public void consume(String payload) throws Exception {
        NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);
        validate(event);
        NotificationResponse notification = notificationService.processEvent(event);
        if (notification.getStatus() == NotificationStatus.SENT) return;
        NotificationTemplate template = templateRepository.findById(event.templateId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.TEMPLATE_NOT_FOUND,
                        "Notification template not found", 404));
        emailSender.send(template, event.recipientEmail(), event.variables());
        notificationService.markAsSent(notification.getId());
    }

    /**
     * Kiểm tra sự kiện: bắt buộc có {@code eventId}, {@code templateId}, email người nhận, kênh {@code EMAIL}, và chưa hết hạn.
     *
     * @param event sự kiện cần kiểm tra
     * @throws NotificationException {@code INVALID_NOTIFICATION_EVENT} (400) khi không hợp lệ
     */
    private void validate(NotificationEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()
                || event.templateId() == null || event.templateId().isBlank()
                || event.recipientEmail() == null || event.recipientEmail().isBlank()
                || event.channel() != NotificationChannel.EMAIL
                || (event.expiresAt() != null && event.expiresAt().isBefore(Instant.now()))) {
            throw new NotificationException(NotificationErrorCode.INVALID_NOTIFICATION_EVENT,
                    "Invalid notification event", 400);
        }
    }
}
