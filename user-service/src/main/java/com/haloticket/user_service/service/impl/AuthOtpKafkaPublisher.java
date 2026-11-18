package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.service.AuthApplicationService.AuthException;
import com.haloticket.user_service.service.OtpDeliveryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Gửi OTP tới notification qua Kafka để gửi email, theo cổng {@link OtpDeliveryPort}. */
@Component
public class AuthOtpKafkaPublisher implements OtpDeliveryPort {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    /** Tạo bộ gửi với KafkaTemplate và tên topic (mặc định {@code haloticket.auth.otp.v1}). */
    public AuthOtpKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                 @Value("${auth.otp.kafka.topic:haloticket.auth.otp.v1}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Phát thông điệp chứa email, mục đích, OTP và hạn dùng lên Kafka, đợi broker xác nhận.
     *
     * @param email email nhận mã, cũng là khóa thông điệp
     * @param purpose mục đích OTP
     * @param otp mã OTP dạng chữ rõ
     * @param expiresAt thời điểm hết hạn
     * @throws AuthException HTTP 503 {@code OTP_DELIVERY_UNAVAILABLE} khi không gửi được
     */
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
