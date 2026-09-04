package com.haloticket.user_service.auth.delivery;

import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.auth.service.OtpDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AuthOtpKafkaPublisher implements OtpDeliveryPort {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public AuthOtpKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                 @Value("${auth.otp.kafka.topic:haloticket.auth.otp.v1}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void send(String email, OtpPurpose purpose, String otp, Instant expiresAt) {
        try {
            kafkaTemplate.send(topic, email, Map.of(
                    "email", email,
                    "purpose", purpose.name(),
                    "otp", otp,
                    "expiresAt", expiresAt.toString()
            )).get();
        } catch (Exception exception) {
            throw new AuthException("OTP_DELIVERY_UNAVAILABLE", "Verification email delivery is unavailable", 503);
        }
    }
}
