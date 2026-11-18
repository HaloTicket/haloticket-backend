package com.haloticket.notification_service.auth;

import org.springframework.beans.factory.annotation.Value;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;
import com.haloticket.notification_service.event.EmailTemplateRenderer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Gửi email chứa mã OTP xác minh (đăng ký tài khoản hoặc đặt lại mật khẩu) bằng mẫu HTML.
 */
@Service
public class AuthOtpEmailSender {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JavaMailSender mailSender;
    private final String from;
    private final EmailTemplateRenderer renderer;

    /**
     * Tạo bộ gửi email.
     *
     * @param mailSender bộ gửi thư của Spring
     * @param from địa chỉ người gửi, lấy từ {@code notification.mail.from}
     * @param renderer bộ dựng nội dung từ mẫu email
     */
    public AuthOtpEmailSender(JavaMailSender mailSender,
                              @Value("${notification.mail.from:no-reply@haloticket.local}") String from,
                              EmailTemplateRenderer renderer) {
        this.mailSender = mailSender;
        this.from = from;
        this.renderer = renderer;
    }

    /**
     * Gửi email OTP tới người nhận. Dùng mẫu {@code forgot-password.html} khi mục đích là {@code PASSWORD_RESET}, ngược lại dùng
     * {@code email-verification.html}; thời hạn mã được định dạng theo giờ Việt Nam.
     *
     * @param event sự kiện OTP gồm email, mục đích, mã và thời hạn
     * @throws NotificationException với mã {@code EMAIL_DELIVERY_FAILED} (HTTP 503) khi gửi thất bại; ngoại lệ {@code NotificationException}
     *         phát sinh khi dựng mẫu được ném nguyên vẹn
     */
    public void send(AuthOtpEvent event) {
        String template = "PASSWORD_RESET".equals(event.purpose()) ? "forgot-password.html" : "email-verification.html";
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(event.email());
            helper.setSubject("Mã xác minh HaloTicket");
            helper.setText(renderer.render(template, Map.of(
                    "otp", event.otp(), "expiresAt", EXPIRY.format(event.expiresAt()),
                    "purpose", purposeLabel(event.purpose()))), true);
            mailSender.send(message);
        } catch (MessagingException | RuntimeException exception) {
            if (exception instanceof NotificationException notificationException) throw notificationException;
            throw new NotificationException(NotificationErrorCode.EMAIL_DELIVERY_FAILED,
                    "Email delivery failed", 503, exception);
        }
    }

    /**
     * Đổi mục đích OTP thành cụm từ tiếng Việt đưa vào nội dung email.
     *
     * @param purpose mục đích OTP
     * @return {@code "đặt lại mật khẩu"} cho {@code PASSWORD_RESET}, ngược lại {@code "đăng ký tài khoản"}
     */
    private String purposeLabel(String purpose) {
        return "PASSWORD_RESET".equals(purpose) ? "đặt lại mật khẩu" : "đăng ký tài khoản";
    }
}
