package com.haloticket.notification_service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthOtpKafkaConsumer {
    private final ObjectMapper objectMapper;
    private final AuthOtpEmailSender emailSender;

    @KafkaListener(topics = "${notification.kafka.auth-otp-topic:haloticket.auth.otp.v1}",
            groupId = "${notification.kafka.auth-otp-group:notification-auth-otp}")
    public void consume(String payload) throws Exception {
        AuthOtpEvent event = objectMapper.readValue(payload, AuthOtpEvent.class);
        if (event.email() == null || event.email().isBlank() || event.otp() == null
                || !event.otp().matches("\\d{6}") || event.expiresAt() == null
                || event.expiresAt().isBefore(java.time.Instant.now())) {
            throw new IllegalArgumentException("Invalid or expired OTP event");
        }
        emailSender.send(event);
    }
}
