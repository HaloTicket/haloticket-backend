package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaNotificationListenerTest {
    @Mock NotificationEmailSender emailSender;
    @Mock NotificationService notificationService;
    @Mock JavaMailSender mailSender;
    @Mock NotificationTemplateRepository templateRepository;

    private SagaNotificationListener listener;
    private NotificationTemplate template;

    @BeforeEach
    void setUp() {
        listener = new SagaNotificationListener(new ObjectMapper(), emailSender, notificationService,
                mailSender, new EmailTemplateRenderer(), templateRepository);
        ReflectionTestUtils.setField(listener, "frontendBaseUrl", "https://haloticket.vn");
        template = NotificationTemplate.builder().id("confirmation").type(NotificationType.TICKET_CONFIRMATION)
                .channel(NotificationChannel.EMAIL).status(NotificationTemplateStatus.ACTIVE)
                .subjectTemplate("Order {{bookingCode}}").build();
    }

    @Test
    void issuedBatchEmailShowsExactOrderAndEscapesTicketText() {
        when(templateRepository.findByTypeAndChannelAndStatus(any(), any(), any())).thenReturn(Optional.of(template));
        when(notificationService.processEvent(any())).thenReturn(NotificationResponse.builder().id("notification-1")
                .status(NotificationStatus.PENDING).build());
        String payload = """
                {"bookingId":"booking-1","bookingCode":"HT-123","customerId":"customer-1",
                "customerEmail":"buyer@example.com","customerName":"Mai <script> {{ticketRows}}",
                "eventTitle":"Jazz & Blues","amount":250000,
                "tickets":[{"ticketTypeName":"VIP <front>","section":"A","row":"B","seat":"12"},
                {"ticketTypeName":"Standard","section":"","row":"","seat":""}]}
                """;

        listener.handleTicketsIssuedNotification(payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailSender).send(eq(template), eq("buyer@example.com"), vars.capture());
        String html = new EmailTemplateRenderer().render("order-confirmation.html", vars.getValue());
        assertThat(html).contains("HT-123", "Jazz &amp; Blues", "VIP &lt;front&gt;", "Khu A", "Hàng B", "Ghế 12", "Standard", "Vé tự do", "2 vé", "250.000 ₫", "https://haloticket.vn/my-tickets/orders/HT-123");
        assertThat(html).contains("&#123;&#123;ticketRows&#125;&#125;");
        assertThat(html).doesNotContain("<script>", "http://localhost:5173/my-tickets");
        verify(notificationService).markAsSent("notification-1");
    }

    @Test
    void replayOfSentBatchDoesNotSendAgain() {
        when(templateRepository.findByTypeAndChannelAndStatus(any(), any(), any())).thenReturn(Optional.of(template));
        when(notificationService.processEvent(any())).thenReturn(NotificationResponse.builder().id("notification-1")
                .status(NotificationStatus.SENT).build());

        listener.handleTicketsIssuedNotification("""
                {"bookingId":"booking-1","bookingCode":"HT-123","customerEmail":"buyer@example.com",
                "tickets":[{"ticketTypeName":"VIP"}]}
                """);

        verifyNoInteractions(emailSender);
        verify(notificationService, never()).markAsSent(any());
    }

    @Test
    void memberInvitationNeverCarriesAPasswordAndLinksToTheRealRoutes() throws Exception {
        jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        ReflectionTestUtils.setField(listener, "fromAddress", "no-reply@haloticket.local");
        // a payload from an old event-service still carries generatedPassword: it must not reach the mail
        listener.onEventMemberInvitation("""
                {"eventId":"evt-1","eventTitle":"Show <a href=\\"https://evil.example\\">x</a>","email":"invitee@example.com",
                "name":"Lan <b>","role":"MANAGER","isNewUser":true,"generatedPassword":"Hunter2-secret!"}
                """);
        ArgumentCaptor<jakarta.mail.internet.MimeMessage> sent = ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
        verify(mailSender).send(sent.capture());
        String html = readHtml(sent.getValue());
        assertThat(html).doesNotContain("Hunter2-secret").doesNotContain("evil.example\"").doesNotContain("<b>");
        assertThat(html).contains("https://haloticket.vn/organizer/events/evt-1/manage")
                .contains("https://haloticket.vn/forgot-password").doesNotContain("https://haloticket.vn/event/");
    }

    @Test
    void memberInvitationWithATokenLinksToTheAcceptPageAndEscapesTheToken() throws Exception {
        jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        ReflectionTestUtils.setField(listener, "fromAddress", "no-reply@haloticket.local");
        listener.onEventMemberInvitation("""
                {"eventId":"evt-1","eventTitle":"Show","email":"invitee@example.com","name":"Lan","role":"CHECKIN_STAFF",
                "isNewUser":false,"invitationToken":"abc-DEF_123","invitationExpiresAt":"2026-10-31T10:00"}
                """);
        ArgumentCaptor<jakarta.mail.internet.MimeMessage> sent = ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(readHtml(sent.getValue())).contains("https://haloticket.vn/event-invitations/accept#token=abc-DEF_123");
    }

    private static String readHtml(jakarta.mail.internet.MimeMessage message) throws Exception {
        message.saveChanges();
        Object content = message.getContent();
        return flatten(content);
    }

    private static String flatten(Object content) throws Exception {
        if (content instanceof String text) return text;
        if (content instanceof jakarta.mail.Multipart multipart) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) all.append(flatten(multipart.getBodyPart(i).getContent()));
            return all.toString();
        }
        return "";
    }
}
