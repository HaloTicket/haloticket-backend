package com.haloticket.notification_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.repository.NotificationUpdates;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.event.EmailTemplateRenderer;
import com.haloticket.notification_service.event.NotificationEmailSender;
import com.haloticket.notification_service.event.SagaNotificationListener;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.impl.NotificationServiceImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The extra data of a direct notification (event id, link, flagged fields) reaches the stored notification, and only as a JSON object. */
@ExtendWith(MockitoExtension.class)
class DirectNotificationVariablesTest {
    @Mock NotificationRepository repository;
    @Mock NotificationTemplateRepository templateRepository;
    @Mock NotificationEmailSender emailSender;
    @Mock NotificationService notificationService;
    @Mock JavaMailSender mailSender;

    private NotificationServiceImpl serviceWithEchoingRepository() {
        when(repository.insert(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
        var directory = mock(RecipientDirectory.class);
        when(directory.canonicalIdOrAlias("u1")).thenReturn("u1");
        return new NotificationServiceImpl(repository, templateRepository, new ObjectMapper(),
                mock(NotificationUpdates.class), directory, mock(SimpMessagingTemplate.class));
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void aJsonObjectIsStoredAndComesBackInTheResponse() {
        String variables = "{\"eventId\":\"ev1\",\"link\":\"/organizer/events/ev1/manage/edit\",\"flaggedFields\":[\"title\"]}";

        NotificationResponse response = serviceWithEchoingRepository()
                .publishDirectNotification("u1", "EVENT_REJECTED", "Từ chối", "Lý do", variables);

        assertThat(saved().getVariablesJson()).isEqualTo(variables);
        assertThat(response.getVariablesJson()).isEqualTo(variables);
    }

    @Test
    void anythingThatIsNotASmallJsonObjectIsDropped() {
        for (String bad : new String[]{null, "", "  ", "not json", "[1,2]", "\"text\"", "{\"a\":\"" + "x".repeat(5000) + "\"}"}) {
            org.mockito.Mockito.clearInvocations(repository);
            serviceWithEchoingRepository().publishDirectNotification("u1", "T", "t", "m", bad);
            assertThat(saved().getVariablesJson()).as("input %s", bad == null ? "null" : bad.substring(0, Math.min(12, bad.length()))).isNull();
        }
    }

    @Test
    void theFourArgumentFormStillWorksWithoutVariables() {
        serviceWithEchoingRepository().publishDirectNotification("u1", "T", "t", "m");
        assertThat(saved().getVariablesJson()).isNull();
    }

    @Test
    void theKafkaListenerPassesTheVariablesOn() throws Exception {
        SagaNotificationListener listener = new SagaNotificationListener(new ObjectMapper(), emailSender, notificationService,
                mailSender, new EmailTemplateRenderer(), templateRepository);
        ReflectionTestUtils.setField(listener, "frontendBaseUrl", "https://haloticket.vn");
        String vars = "{\"eventId\":\"ev1\"}";
        String payload = "{\"userId\":\"u1\",\"recipientUserId\":\"u1\",\"type\":\"EVENT_REJECTED\",\"title\":\"Từ chối\",\"message\":\"Lý do\","
                + "\"variables\":\"{\\\"eventId\\\":\\\"ev1\\\"}\"}";

        listener.handleDirectAndPayoutNotifications(new ConsumerRecord<>("test-direct", 0, 1, "u1", payload));

        verify(notificationService).publishDirectEvent(eq("kafka:test-direct:0:1"), eq("u1"), eq("EVENT_REJECTED"), eq("Từ chối"), eq("Lý do"), eq(vars));
    }

    @Test
    void aPayloadWithoutVariablesStillWorks() throws Exception {
        SagaNotificationListener listener = new SagaNotificationListener(new ObjectMapper(), emailSender, notificationService,
                mailSender, new EmailTemplateRenderer(), templateRepository);
        ReflectionTestUtils.setField(listener, "frontendBaseUrl", "https://haloticket.vn");

        listener.handleDirectAndPayoutNotifications(new ConsumerRecord<>("test-direct", 0, 1, "u1", "{\"userId\":\"u1\",\"type\":\"PAYOUT_APPROVED\",\"title\":\"t\",\"message\":\"m\"}"));

        verify(notificationService).publishDirectEvent("kafka:test-direct:0:1", "u1", "PAYOUT_APPROVED", "t", "m", null);
    }
}
