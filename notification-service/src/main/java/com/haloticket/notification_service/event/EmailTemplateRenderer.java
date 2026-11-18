package com.haloticket.notification_service.event;

import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Dựng nội dung email HTML từ tệp mẫu trong {@code templates/} bằng cách thay các chỗ giữ {@code {{biến}}}.
 * Giá trị mặc định được escape HTML; chỉ các biến khai báo trong {@code HTML_VARIABLES} được chèn nguyên văn.
 */
@Component
public class EmailTemplateRenderer {

    /** Các biến được chèn nguyên văn dưới dạng HTML, theo từng mẫu. Biến không có trong danh sách sẽ bị escape. */
    static final Map<String, Set<String>> HTML_VARIABLES = Map.of(
            "order-confirmation.html", Set.of("ticketRows"),
            "gift-invitation.html", Set.of("messageBlock"),
            "event-member-invitation.html", Set.of("newUserContent", "setPasswordBlock"));

    /**
     * Escape văn bản để đặt vào nội dung HTML hoặc thuộc tính trong ngoặc kép; escape cả dấu ngoặc nhọn
     * để một giá trị không thể trở thành chỗ giữ biến khác.
     *
     * @param value giá trị gốc, null được coi là chuỗi rỗng
     * @return chuỗi đã escape
     */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
                .replace("{", "&#123;").replace("}", "&#125;");
    }

    /**
     * Đọc mẫu {@code templates/<templateName>} và thay các {@code {{biến}}} bằng giá trị tương ứng
     * (escape trừ các biến HTML của mẫu đó).
     *
     * @param templateName tên tệp mẫu, ví dụ {@code order-confirmation.html}
     * @param variables biến thay thế, có thể null
     * @return HTML đã thay biến
     * @throws NotificationException với mã {@code EMAIL_TEMPLATE_NOT_FOUND} (HTTP 500) khi không đọc được tệp mẫu
     */
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
