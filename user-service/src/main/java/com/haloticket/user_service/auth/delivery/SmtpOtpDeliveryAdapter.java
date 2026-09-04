package com.haloticket.user_service.auth.delivery;

import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.service.OtpDeliveryPort;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class SmtpOtpDeliveryAdapter implements OtpDeliveryPort {
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JavaMailSender mailSender;
    private final String from;
    private final int expiryMinutes;

    public SmtpOtpDeliveryAdapter(JavaMailSender mailSender, String from, int expiryMinutes) {
        this.mailSender = mailSender;
        this.from = from;
        this.expiryMinutes = expiryMinutes;
    }

    @Override
    public void send(String email, OtpPurpose purpose, String otp, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Mã xác minh HaloTicket");
        message.setText("Mã xác minh HaloTicket của bạn là: " + otp + "\n\n"
                + "Mã dùng cho " + purposeLabel(purpose) + " và hết hạn lúc "
                + EXPIRY_FORMAT.format(expiresAt) + ".\n"
                + "Mã có hiệu lực trong " + expiryMinutes + " phút. Không chia sẻ mã này với người khác.");
        mailSender.send(message);
    }

    private String purposeLabel(OtpPurpose purpose) {
        return purpose == OtpPurpose.PASSWORD_RESET ? "đặt lại mật khẩu" : "đăng ký tài khoản";
    }
}
