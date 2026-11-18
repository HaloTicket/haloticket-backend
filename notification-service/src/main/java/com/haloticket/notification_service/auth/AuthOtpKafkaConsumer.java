package com.haloticket.notification_service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import com.haloticket.notification_service.exception.NotificationErrorCode;
import com.haloticket.notification_service.exception.NotificationException;

/**
 * Consumer Kafka nhận sự kiện OTP xác thực và chuyển cho {@code AuthOtpEmailSender} để gửi email.
 */
@Component
@RequiredArgsConstructor
public class AuthOtpKafkaConsumer {
    private final ObjectMapper objectMapper;
    private final AuthOtpEmailSender emailSender;

    /**
     * Xử lý một thông điệp OTP: đọc JSON, kiểm tra có email, mã đúng 6 chữ số và chưa hết hạn, rồi gửi email.
     * Lỗi được ném lên để error handler thử lại và chuyển sang topic DLT khi vẫn thất bại.
     *
     * @param payload nội dung JSON của sự kiện OTP
     * @throws Exception khi không đọc được JSON hoặc gửi email thất bại
     * @throws NotificationException với mã {@code INVALID_NOTIFICATION_EVENT} (HTTP 400) khi sự kiện không hợp lệ hoặc đã hết hạn
     */
    @KafkaListener(topics = "${notification.kafka.auth-otp-topic:haloticket.auth.otp.v1}",
            groupId = "${notification.kafka.auth-otp-group:notification-auth-otp}")
    public void consume(String payload) throws Exception {
        AuthOtpEvent event = objectMapper.readValue(payload, AuthOtpEvent.class);
        if (event.email() == null || event.email().isBlank() || event.otp() == null
                || !event.otp().matches("\\d{6}") || event.expiresAt() == null
                || event.expiresAt().isBefore(java.time.Instant.now())) {
            throw new NotificationException(NotificationErrorCode.INVALID_NOTIFICATION_EVENT,
                    "Invalid or expired OTP event", 400);
        }
        emailSender.send(event);
    }
}
