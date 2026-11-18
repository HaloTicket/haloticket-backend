package com.haloticket.notification_service.event;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import com.haloticket.notification_service.enums.NotificationType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Gửi email thông báo: chọn tệp HTML theo loại mẫu, dựng nội dung rồi gửi qua {@code JavaMailSender}. */
@Service
public class NotificationEmailSender {
    private final JavaMailSender mailSender;
    private final String from;
    private final EmailTemplateRenderer renderer;

    /**
     * Khởi tạo bộ gửi email.
     *
     * @param mailSender bộ gửi thư của Spring
     * @param from địa chỉ người gửi, cấu hình {@code notification.mail.from}
     * @param renderer bộ dựng HTML từ tệp mẫu
     */
    public NotificationEmailSender(JavaMailSender mailSender,
            @Value("${notification.mail.from:no-reply@haloticket.local}") String from,
            EmailTemplateRenderer renderer) {
        this.mailSender = mailSender;
        this.from = from;
        this.renderer = renderer;
    }

    /**
     * Gửi email HTML tới người nhận: tiêu đề lấy từ mẫu đã thay biến, thân dựng từ tệp HTML tương ứng loại mẫu.
     *
     * @param template mẫu thông báo (loại và mẫu tiêu đề)
     * @param recipient email người nhận
     * @param variables biến thay thế
     * @throws NotificationException {@code RECIPIENT_EMAIL_REQUIRED} (400) khi thiếu email;
     * {@code EMAIL_DELIVERY_FAILED} (503) khi gửi thư lỗi
     */
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
            if (exception instanceof NotificationException notificationException)
                throw notificationException;
            throw new NotificationException(NotificationErrorCode.EMAIL_DELIVERY_FAILED,
                    "Email delivery failed", 503, exception);
        }
    }

    /**
     * Chọn tệp HTML theo loại thông báo: xác nhận vé, kết quả eKYC, còn lại dùng mẫu chào mừng.
     *
     * @param type loại thông báo
     * @return tên tệp mẫu trong {@code templates/}
     */
    private String fileFor(NotificationType type) {
        return switch (type) {
            case TICKET_CONFIRMATION -> "order-confirmation.html";
            case EKYC_RESULT -> "email-verification.html";
            default -> "welcome.html";
        };
    }

    /**
     * Thay {@code {{biến}}} trong chuỗi mẫu tiêu đề bằng giá trị tương ứng (không escape vì tiêu đề là văn bản thuần).
     *
     * @param template chuỗi mẫu, null được coi là rỗng
     * @param variables biến thay thế, có thể null
     * @return chuỗi đã thay biến
     */
    private String render(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template;
        if (variables != null)
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        return rendered;
    }
}
