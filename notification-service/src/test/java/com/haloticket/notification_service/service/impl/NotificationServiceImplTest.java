package com.haloticket.notification_service.service.impl;

import com.haloticket.notification_service.enums.InboxChannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.repository.NotificationUpdates;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.time.Instant;
import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.event.NotificationEvent;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.haloticket.notification_service.exception.NotificationException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationTemplateRepository notificationTemplateRepository;

    @Mock private NotificationUpdates updates;
    @Mock private RecipientDirectory directory;
    @Mock private SimpMessagingTemplate messaging;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
                notificationRepository,
                notificationTemplateRepository,
                new ObjectMapper(), updates, directory, messaging
        );
    }

    @Test
    void createTemplateDefaultsStatusToActive() {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setType(NotificationType.TICKET_CONFIRMATION);
        request.setChannel(NotificationChannel.EMAIL);
        request.setSubjectTemplate("Ticket confirmed");
        request.setBodyTemplate("Hi {{name}}, your ticket {{ticketCode}} is ready.");

        when(notificationTemplateRepository.insert(any(NotificationTemplate.class)))
                .thenAnswer(invocation -> {
                    NotificationTemplate template = invocation.getArgument(0);
                    template.setId("template-1");
                    return template;
                });

        NotificationTemplateResponse response = notificationService.createTemplate(request);

        assertThat(response.getId()).isEqualTo("template-1");
        assertThat(response.getType()).isEqualTo(NotificationType.TICKET_CONFIRMATION);
        assertThat(response.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(response.getStatus()).isEqualTo(NotificationTemplateStatus.ACTIVE);
    }

    @Test
    void createNotificationFromActiveTemplateStoresUserVariablesAndPendingStatus() {
        NotificationTemplate template = NotificationTemplate.builder()
                .id("template-1")
                .type(NotificationType.EVENT_REMINDER)
                .channel(NotificationChannel.PUSH)
                .subjectTemplate("Event reminder")
                .bodyTemplate("Your event starts at {{startTime}}.")
                .status(NotificationTemplateStatus.ACTIVE)
                .build();

        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setTemplateId("template-1");
        request.setUserId("user-1");
        request.setVariables(Map.of("startTime", "19:00"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        when(notificationTemplateRepository.findById("template-1")).thenReturn(Optional.of(template));
        when(directory.canonicalIdOrAlias("user-1")).thenReturn("user-1");
        when(notificationRepository.insert(captor.capture()))
                .thenAnswer(invocation -> {
                    Notification notification = invocation.getArgument(0);
                    notification.setId("notification-1");
                    return notification;
                });

        NotificationResponse response = notificationService.createNotification(request);

        Notification saved = captor.getValue();
        assertThat(saved.getTemplateId()).isEqualTo("template-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getVariablesJson()).isEqualTo("{\"startTime\":\"19:00\"}");
        assertThat(response.getId()).isEqualTo("notification-1");
        assertThat(response.getChannel()).isEqualTo(NotificationChannel.PUSH);
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void createNotificationRejectsInactiveTemplate() {
        NotificationTemplate template = NotificationTemplate.builder()
                .id("template-1")
                .status(NotificationTemplateStatus.INACTIVE)
                .build();

        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setTemplateId("template-1");
        request.setUserId("user-1");

        when(notificationTemplateRepository.findById("template-1")).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("active");
    }

    @Test
    void markAsSentSetsSentStatusAndTimestamp() {
        Notification notification = Notification.builder()
                .id("notification-1")
                .status(NotificationStatus.SENT)
                .sentAt(Instant.now())
                .build();

        when(updates.setStatus("notification-1", NotificationStatus.SENT)).thenReturn(notification);

        NotificationResponse response = notificationService.markAsSent("notification-1");

        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(response.getSentAt()).isNotNull();
    }

    @Test
    void getUserNotificationsReturnsNewestFirstFromRepository() {
        Notification notification = Notification.builder()
                .id("notification-1")
                .userId("user-1")
                .status(NotificationStatus.PENDING)
                .build();

        when(updates.inbox(List.of("user-1"), InboxChannel.PUBLIC)).thenReturn(List.of(notification));

        List<NotificationResponse> responses = notificationService.getUserNotifications(List.of("user-1"));

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getId()).isEqualTo("notification-1");
    }

    @Test
    void processEventAssociatesNotificationWithPurchaserId() {
        NotificationEvent event = new NotificationEvent("order-confirmation:booking-1", "template-1",
                "customer-1", "buyer@example.com", NotificationChannel.EMAIL, Map.of(), null);
        when(directory.canonicalIdOrAlias("customer-1")).thenReturn("customer-1");
        when(notificationRepository.findByEventId(event.eventId())).thenReturn(Optional.empty());
        when(notificationRepository.insert(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationResponse response = notificationService.processEvent(event);

        assertThat(response.getUserId()).isEqualTo("customer-1");
        assertThat(response.getRecipientEmail()).isEqualTo("buyer@example.com");
    }
}
