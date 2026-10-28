package com.haloticket.notification_service.event;

import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public class EmailTemplateRenderer {

    /** Variables that are inserted as HTML, per template. Anything not listed here is escaped. */
    static final Map<String, Set<String>> HTML_VARIABLES = Map.of(
            "order-confirmation.html", Set.of("ticketRows"),
            "gift-invitation.html", Set.of("messageBlock"),
            "event-member-invitation.html", Set.of("newUserContent", "setPasswordBlock"));

    /** Text for an HTML text node or a quoted attribute; braces too, so a value cannot become another placeholder. */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
                .replace("{", "&#123;").replace("}", "&#125;");
    }

    public String render(String templateName, Map<String, String> variables) {
        try {
            String html = new ClassPathResource("templates/" + templateName).getContentAsString(StandardCharsets.UTF_8);
            if (variables != null) {
                Set<String> asHtml = HTML_VARIABLES.getOrDefault(templateName, Set.of());
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    String value = asHtml.contains(entry.getKey()) ? (entry.getValue() == null ? "" : entry.getValue()) : escape(entry.getValue());
                    html = html.replace("{{" + entry.getKey() + "}}", value);
                }
            }
            return html;
        } catch (IOException exception) {
            throw new NotificationException(NotificationErrorCode.EMAIL_TEMPLATE_NOT_FOUND,
                    "Email template is unavailable", 500, exception);
        }
    }
}
