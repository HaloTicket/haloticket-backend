package com.haloticket.notification_service.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import com.haloticket.notification_service.event.NotificationEvent;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public NotificationTemplateResponse createTemplate(NotificationTemplateCreateRequest request) {
        NotificationTemplate template = NotificationTemplate.builder()
                .type(request.getType())
                .channel(request.getChannel())
                .subjectTemplate(request.getSubjectTemplate())
                .bodyTemplate(request.getBodyTemplate())
                .status(request.getStatus() == null ? NotificationTemplateStatus.ACTIVE : request.getStatus())
                .build();

        return toTemplateResponse(notificationTemplateRepository.save(template));
    }

    @Override
    public List<NotificationTemplateResponse> getTemplates() {
        return notificationTemplateRepository.findAll().stream()
                .map(this::toTemplateResponse)
                .toList();
    }

    @Override
    public NotificationResponse createNotification(NotificationCreateRequest request) {
        NotificationTemplate template = notificationTemplateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.TEMPLATE_NOT_FOUND,
                        "Notification template not found", 404));
        if (template.getStatus() != NotificationTemplateStatus.ACTIVE) {
            throw new NotificationException(NotificationErrorCode.TEMPLATE_INACTIVE,
                    "Notification template must be active", 409);
        }

        Notification notification = Notification.builder()
                .template(template)
                .userId(request.getUserId())
                .variablesJson(toVariablesJson(request.getVariables()))
                .channel(request.getChannel() == null ? template.getChannel() : request.getChannel())
                .status(NotificationStatus.PENDING)
                .build();

        return toNotificationResponse(notificationRepository.save(notification));
    }

    @Override
    public List<NotificationResponse> getUserNotifications(String userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toNotificationResponse)
                .toList();
    }

    @Override
    public NotificationResponse markAsSent(String id) {
        Notification notification = findNotification(id);
        notification.setStatus(NotificationStatus.SENT);
        notification.setSentAt(LocalDateTime.now());
        return toNotificationResponse(notificationRepository.save(notification));
    }

    @Override
    public NotificationResponse markAsFailed(String id) {
        Notification notification = findNotification(id);
        notification.setStatus(NotificationStatus.FAILED);
        return toNotificationResponse(notificationRepository.save(notification));
    }

    @Override
    public NotificationResponse markAsRead(String id) {
        Notification notification = findNotification(id);
        notification.setReadStatus(true);
        return toNotificationResponse(notificationRepository.save(notification));
    }

    @Override
    public List<NotificationResponse> markAllAsRead(String userId) {
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification n : notifications) {
            n.setReadStatus(true);
        }
        return notificationRepository.saveAll(notifications).stream()
                .map(this::toNotificationResponse)
                .toList();
    }

    @Override
    public NotificationResponse processEvent(NotificationEvent event) {
        NotificationResponse res = notificationRepository.findByEventId(event.eventId())
                .map(this::toNotificationResponse)
                .orElseGet(() -> {
                    Notification notification = Notification.builder()
                            .eventId(event.eventId())
                            .userId(event.recipientEmail())
                            .recipientEmail(event.recipientEmail())
                            .variablesJson(toVariablesJson(event.variables()))
                            .channel(event.channel())
                            .status(NotificationStatus.PENDING)
                            .build();

                    return toNotificationResponse(notificationRepository.save(notification));
                });
        broadcastStompNotification(res);
        return res;
    }

    @Override
    public NotificationResponse publishDirectNotification(String userId, String type, String title, String message) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .readStatus(false)
                .channel(com.haloticket.notification_service.enums.NotificationChannel.EMAIL)
                .status(NotificationStatus.SENT)
                .build();
        Notification saved = notificationRepository.save(notification);
        NotificationResponse response = toNotificationResponse(saved);
        broadcastStompNotification(response);
        return response;
    }

    private void broadcastStompNotification(NotificationResponse response) {
        try {
            if (messagingTemplate != null && response != null) {
                if ("ADMIN".equalsIgnoreCase(response.getUserId()) || "NEW_EVENT_APPROVAL".equalsIgnoreCase(response.getType())) {
                    messagingTemplate.convertAndSend("/topic/admin-notifications", response);
                }
                if (response.getUserId() != null && !"ADMIN".equalsIgnoreCase(response.getUserId())) {
                    messagingTemplate.convertAndSend("/topic/user-notifications/" + response.getUserId(), response);
                }
            }
        } catch (Exception e) {
            // silent catch for WebSocket broadcast
        }
    }

    private Notification findNotification(String id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification not found", 404));
    }

    private String toVariablesJson(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException exception) {
            throw new NotificationException(NotificationErrorCode.VARIABLES_SERIALIZATION_FAILED,
                    "Notification variables cannot be serialized", 500, exception);
        }
    }

    private NotificationTemplateResponse toTemplateResponse(NotificationTemplate template) {
        return NotificationTemplateResponse.builder()
                .id(template.getId())
                .type(template.getType())
                .channel(template.getChannel())
                .subjectTemplate(template.getSubjectTemplate())
                .bodyTemplate(template.getBodyTemplate())
                .status(template.getStatus())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

    private NotificationResponse toNotificationResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .eventId(notification.getEventId())
                .templateId(notification.getTemplate() == null ? null : notification.getTemplate().getId())
                .userId(notification.getUserId())
                .recipientEmail(notification.getRecipientEmail())
                .variablesJson(notification.getVariablesJson())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .readStatus(notification.getReadStatus())
                .channel(notification.getChannel())
                .status(notification.getStatus())
                .createdAt(notification.getCreatedAt())
                .sentAt(notification.getSentAt())
                .build();
    }
}
