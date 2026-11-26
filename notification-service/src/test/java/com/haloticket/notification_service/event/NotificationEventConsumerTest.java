package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationEventConsumerTest {
    @Test
    void transientMailFailureRetriesPendingAndReplaysOfSentDoNotSendAgain() throws Exception {
        var notifications = mock(NotificationService.class);
        var templates = mock(NotificationTemplateRepository.class);
        var mail = mock(NotificationEmailSender.class);
        var consumer = new NotificationEventConsumer(new ObjectMapper(), notifications, templates, mail);
        var template = NotificationTemplate.builder().id("template").status(NotificationTemplateStatus.ACTIVE).build();
        String payload = """
                {"eventId":"event","templateId":"template","userId":"user","recipientEmail":"user@example.test",
                 "channel":"EMAIL","variables":{"name":"Test"}}
                """;
        var pending = NotificationResponse.builder().id("item").status(NotificationStatus.PENDING).build();
        var sent = NotificationResponse.builder().id("item").status(NotificationStatus.SENT).build();
        when(notifications.processEvent(any())).thenReturn(pending, pending, sent);
        when(templates.findById("template")).thenReturn(Optional.of(template));
        doThrow(new IllegalStateException("SMTP unavailable")).doNothing()
                .when(mail).send(template, "user@example.test", Map.of("name", "Test"));

        assertThatThrownBy(() -> consumer.consume(payload)).hasMessage("SMTP unavailable");
        verify(notifications, never()).markAsSent(anyString());
        consumer.consume(payload);
        consumer.consume(payload);
        verify(mail, times(2)).send(template, "user@example.test", Map.of("name", "Test"));
        verify(notifications).markAsSent("item");
    }
}
