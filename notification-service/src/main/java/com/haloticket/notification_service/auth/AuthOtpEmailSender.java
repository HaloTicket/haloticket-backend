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

@Service
public class AuthOtpEmailSender {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JavaMailSender mailSender;
    private final String from;
    private final EmailTemplateRenderer renderer;

    public AuthOtpEmailSender(JavaMailSender mailSender,
                              @Value("${notification.mail.from:no-reply@haloticket.local}") String from,
                              EmailTemplateRenderer renderer) {
        this.mailSender = mailSender;
        this.from = from;
        this.renderer = renderer;
    }

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

    private String purposeLabel(String purpose) {
        return "PASSWORD_RESET".equals(purpose) ? "đặt lại mật khẩu" : "đăng ký tài khoản";
    }
}
