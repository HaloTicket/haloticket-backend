package com.haloticket.notification_service.event;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import com.haloticket.notification_service.enums.NotificationType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class NotificationEmailSender {
    private final JavaMailSender mailSender;
    private final String from;
    private final EmailTemplateRenderer renderer;

    public NotificationEmailSender(JavaMailSender mailSender,
                                   @Value("${notification.mail.from:no-reply@haloticket.local}") String from,
                                   EmailTemplateRenderer renderer) {
        this.mailSender = mailSender;
        this.from = from;
        this.renderer = renderer;
    }

    public void send(NotificationTemplate template, String recipient, Map<String, String> variables) {
        if (recipient == null || recipient.isBlank()) {
            throw new NotificationException(NotificationErrorCode.RECIPIENT_EMAIL_REQUIRED,
                    "Notification recipient email is required", 400);
        }
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(render(template.getSubjectTemplate(), variables));
            helper.setText(renderer.render(fileFor(template.getType()), variables), true);
            mailSender.send(message);
        } catch (MessagingException | RuntimeException exception) {
            if (exception instanceof NotificationException notificationException) throw notificationException;
            throw new NotificationException(NotificationErrorCode.EMAIL_DELIVERY_FAILED,
                    "Email delivery failed", 503, exception);
        }
    }

    private String fileFor(NotificationType type) {
        return switch (type) {
            case TICKET_CONFIRMATION -> "order-confirmation.html";
            case EKYC_RESULT -> "email-verification.html";
            default -> "welcome.html";
        };
    }

    private String render(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template;
        if (variables != null) for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
