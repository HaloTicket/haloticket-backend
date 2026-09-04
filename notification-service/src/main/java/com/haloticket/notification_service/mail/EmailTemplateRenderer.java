package com.haloticket.notification_service.mail;

import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class EmailTemplateRenderer {
    public String render(String templateName, Map<String, String> variables) {
        try {
            String html = new ClassPathResource("templates/" + templateName).getContentAsString(StandardCharsets.UTF_8);
            if (variables != null) for (Map.Entry<String, String> entry : variables.entrySet()) {
                html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return html;
        } catch (IOException exception) {
            throw new NotificationException(NotificationErrorCode.EMAIL_TEMPLATE_NOT_FOUND,
                    "Email template is unavailable", 500, exception);
        }
    }
}
