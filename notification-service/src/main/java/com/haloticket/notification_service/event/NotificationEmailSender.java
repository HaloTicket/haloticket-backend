package com.haloticket.notification_service.event;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class NotificationEmailSender {
    private final JavaMailSender mailSender;
    private final String from;

    public NotificationEmailSender(JavaMailSender mailSender,
                                   @Value("${notification.mail.from:no-reply@haloticket.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void send(NotificationTemplate template, String recipient, Map<String, String> variables) {
        if (recipient == null || recipient.isBlank()) {
            throw new NotificationException(NotificationErrorCode.RECIPIENT_EMAIL_REQUIRED,
                    "Notification recipient email is required", 400);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(render(template.getSubjectTemplate(), variables));
        message.setText(render(template.getBodyTemplate(), variables));
        try {
            mailSender.send(message);
        } catch (RuntimeException exception) {
            throw new NotificationException(NotificationErrorCode.EMAIL_DELIVERY_FAILED,
                    "Email delivery failed", 503, exception);
        }
    }

    private String render(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template;
        if (variables != null) for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
