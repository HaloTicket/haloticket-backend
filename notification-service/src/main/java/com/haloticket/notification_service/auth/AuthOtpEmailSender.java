package com.haloticket.notification_service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class AuthOtpEmailSender {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JavaMailSender mailSender;
    private final String from;

    public AuthOtpEmailSender(JavaMailSender mailSender,
                              @Value("${notification.mail.from:no-reply@haloticket.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void send(AuthOtpEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.email());
        message.setSubject("Mã xác minh HaloTicket");
        message.setText("Mã xác minh HaloTicket của bạn là: " + event.otp() + "\n\n"
                + "Mã dùng cho " + purposeLabel(event.purpose()) + " và hết hạn lúc "
                + EXPIRY.format(event.expiresAt()) + ".\n"
                + "Không chia sẻ mã này với người khác.");
        mailSender.send(message);
    }

    private String purposeLabel(String purpose) {
        return "PASSWORD_RESET".equals(purpose) ? "đặt lại mật khẩu" : "đăng ký tài khoản";
    }
}
