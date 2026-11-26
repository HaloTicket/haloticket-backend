package com.haloticket.notification_service.service.impl;

import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.enums.NotificationVisibility;

import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.enums.NotificationSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationPriority;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.event.NotificationEvent;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.repository.NotificationUpdates;
import com.haloticket.notification_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    private static final ZoneId LEGACY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final ObjectMapper objectMapper;
    private final NotificationUpdates updates;
    private final RecipientDirectory directory;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public NotificationTemplateResponse createTemplate(NotificationTemplateCreateRequest request) {
        Instant now = Instant.now();
        var template = NotificationTemplate.builder()
                .id(UUID.randomUUID().toString())
                .type(request.getType())
                .channel(request.getChannel())
                .subjectTemplate(request.getSubjectTemplate())
                .bodyTemplate(request.getBodyTemplate())
                .status(request.getStatus() == null ? NotificationTemplateStatus.ACTIVE : request.getStatus())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return toTemplateResponse(notificationTemplateRepository.insert(template));
    }

    @Override
    public List<NotificationTemplateResponse> getTemplates() {
        return notificationTemplateRepository.findAll().stream().map(this::toTemplateResponse).toList();
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
        var notification = newNotification(directory.canonicalIdOrAlias(request.getUserId()))
                .templateId(template.getId())
                .variablesJson(toVariablesJson(request.getVariables()))
                .channel(request.getChannel() == null ? template.getChannel() : request.getChannel())
                .status(NotificationStatus.PENDING)
                .build();
        return toNotificationResponse(notificationRepository.insert(notification));
    }

    @Override
    public List<NotificationResponse> getUserNotifications(Collection<String> aliases, InboxChannel channel) {
        return updates.inbox(aliases, channel).stream().map(this::toNotificationResponse).toList();
    }

    @Override
    public NotificationResponse markAsSent(String id) {
        return requireFound(updates.setStatus(id, NotificationStatus.SENT));
    }

    @Override
    public NotificationResponse markAsFailed(String id) {
        return requireFound(updates.setStatus(id, NotificationStatus.FAILED));
    }

    @Override
    public NotificationResponse markAsRead(String id, Collection<String> aliases) {
        return requireFound(updates.markRead(id, aliases));
    }

    @Override
    public NotificationResponse markAsUnread(String id, Collection<String> aliases) {
        return requireFound(updates.markUnread(id, aliases));
    }

    @Override
    public List<NotificationResponse> markAllAsRead(Collection<String> aliases, Instant startedAt, InboxChannel channel) {
        return updates.markAllRead(aliases, startedAt, channel).stream().map(this::toNotificationResponse).toList();
    }

    @Override
    public void delete(String id, Collection<String> aliases) {
        if (!updates.softDelete(id, aliases)) {
            throw notFound();
        }
    }

    @Override
    public NotificationResponse processEvent(NotificationEvent event) {
        return processEvent(event, null, null, null);
    }

    @Override
    public NotificationResponse processEvent(NotificationEvent event, String type, String title, String message) {
        String eventId = normalizeEventId(event.eventId());
        if (eventId != null) {
            var existing = notificationRepository.findByEventId(eventId);
            if (existing.isPresent()) {
                return toNotificationResponse(existing.get());
            }
        }
        String recipient = event.userId() == null || event.userId().isBlank() ? event.recipientEmail() : event.userId();
        var notification = newNotification(directory.canonicalIdOrAlias(recipient))
                .eventId(eventId)
                .templateId(event.templateId())
                .recipientEmail(event.recipientEmail())
                .type(type)
                .title(title)
                .message(message)
                .variablesJson(toVariablesJson(event.variables()))
                .channel(event.channel())
                .status(NotificationStatus.PENDING)
                .build();
        return insertAndBroadcast(notification);
    }

    @Override
    public NotificationResponse publishDirectNotification(String userId, String type, String title, String message, String variablesJson) {
        return publishDirectEvent(null, userId, type, title, message, variablesJson);
    }

    @Override
    public NotificationResponse publishDirectEvent(String eventId, String userId, String type, String title, String message,
                                                       String variablesJson) {
        var notification = newNotification(directory.canonicalIdOrAlias(userId))
                .eventId(normalizeEventId(eventId))
                .type(type)
                .title(title)
                .message(message)
                .variablesJson(validVariables(variablesJson))
                .channel(NotificationChannel.EMAIL)
                .status(NotificationStatus.SENT)
                .build();
        return insertAndBroadcast(notification);
    }

    public void deliverAnnouncement(String announcementId, String userId, String title, String message, NotificationPriority priority) {
        var notification = announcement(announcementId, userId, title, message, priority)
                .type("SYSTEM_ANNOUNCEMENT")
                .build();
        insertAndBroadcast(notification);
    }

    public void deliverOrganizerAnnouncement(AdminAnnouncement job, String userId) {
        var payload = job.getPayload();
        var notification = announcement(job.getId(), userId, payload.title(), payload.message(), payload.priority())
                .type("ORGANIZER_ANNOUNCEMENT")
                .organizerEventId(job.getOrganizerEventId())
                .source(NotificationSource.ORGANIZER)
                .eventTitle(job.getEventTitle())
                .category(job.getCategory())
                .visibility(job.effectiveTarget().staff() ? NotificationVisibility.STAFF : NotificationVisibility.PUBLIC)
                .build();
        insertAndBroadcast(notification);
    }

    private Notification.NotificationBuilder announcement(String announcementId, String userId, String title,
                                                           String message, NotificationPriority priority) {
        return newNotification(userId)
                .eventId("announcement:" + announcementId + ":" + userId)
                .title(title)
                .message(message)
                .priority(priority)
                .channel(NotificationChannel.PUSH)
                .status(NotificationStatus.SENT)
                .sentAt(Instant.now());
    }

    private NotificationResponse insertAndBroadcast(Notification notification) {
        Notification inserted;
        try {
            inserted = notificationRepository.insert(notification);
        } catch (DuplicateKeyException collision) {
            if (notification.getEventId() == null) {
                throw collision;
            }
            return notificationRepository.findByEventId(notification.getEventId())
                    .map(this::toNotificationResponse)
                    .orElseThrow(() -> collision);
        }
        var response = toNotificationResponse(inserted);
        broadcast(response);
        return response;
    }

    private void broadcast(NotificationResponse response) {
        try {
            if (response.getVisibility() == NotificationVisibility.STAFF) {
                messagingTemplate.convertAndSend("/topic/staff-notifications/" + response.getUserId(), response);
            } else if ("ADMIN".equalsIgnoreCase(response.getUserId())) {
                messagingTemplate.convertAndSend("/topic/admin-notifications", response);
            } else {
                messagingTemplate.convertAndSend("/topic/user-notifications/" + response.getUserId(), response);
            }
        } catch (RuntimeException exception) {
            log.warn("Realtime delivery failed for notification {}", response.getId(), exception);
        }
    }

    private static Notification.NotificationBuilder newNotification(String userId) {
        return Notification.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .priority(NotificationPriority.NORMAL)
                .readStatus(false)
                .createdAt(Instant.now());
    }

    private static String normalizeEventId(String eventId) {
        return eventId == null || eventId.isBlank() ? null : eventId;
    }

    private String validVariables(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4000) return null;
        try {
            return objectMapper.readTree(raw).isObject() ? raw : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private NotificationResponse requireFound(Notification notification) {
        if (notification == null) {
            throw notFound();
        }
        return toNotificationResponse(notification);
    }

    private static NotificationException notFound() {
        return new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found", 404);
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
                .createdAt(localDateTime(template.getCreatedAt()))
                .updatedAt(localDateTime(template.getUpdatedAt()))
                .build();
    }

    private NotificationResponse toNotificationResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .eventId(notification.getOrganizerEventId() == null ? notification.getEventId() : notification.getOrganizerEventId())
                .source(notification.getSource() == null ? NotificationSource.HALOTICKET : notification.getSource())
                .eventTitle(notification.getEventTitle())
                .category(notification.getCategory())
                .visibility(notification.getVisibility() == null ? NotificationVisibility.PUBLIC : notification.getVisibility())
                .templateId(notification.getTemplateId())
                .userId(notification.getUserId())
                .recipientEmail(notification.getRecipientEmail())
                .variablesJson(notification.getVariablesJson())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .type(notification.getType())
                .priority(notification.getPriority() == null ? NotificationPriority.NORMAL : notification.getPriority())
                .readStatus(notification.getReadStatus())
                .channel(notification.getChannel())
                .status(notification.getStatus())
                .createdAt(localDateTime(notification.getCreatedAt()))
                .sentAt(localDateTime(notification.getSentAt()))
                .build();
    }

    private static LocalDateTime localDateTime(Instant timestamp) {
        return timestamp == null ? null : LocalDateTime.ofInstant(timestamp, LEGACY_ZONE);
    }
}
