package com.haloticket.payment_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.PaymentStatus;
import com.haloticket.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lắng nghe sự kiện Kafka liên quan thanh toán. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    /**
     * Khi đơn đặt chỗ được tạo, khởi tạo bản ghi thanh toán {@code PENDING} với số tiền của đơn. Bỏ qua nếu thiếu {@code bookingId}
     * hoặc đã có thanh toán cho đơn; lỗi chỉ được ghi log.
     *
     * @param message JSON sự kiện {@code BOOKING_CREATED}
     */
    @KafkaListener(topics = "haloticket.booking.created.v1", groupId = "payment-service")
    @Transactional
    public void handleBookingCreated(String message) {
        try {
            log.info("Received BOOKING_CREATED event in payment-service: {}", message);
            JsonNode node = objectMapper.readTree(message);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : null;
            String bookingCode = node.has("bookingCode") ? node.get("bookingCode").asText() : null;
            Double totalAmount = node.has("totalAmount") ? node.get("totalAmount").asDouble() : 0.0;

            if (bookingId == null || bookingId.isBlank()) {
                log.warn("BOOKING_CREATED event missing bookingId: {}", message);
                return;
            }

            if (paymentRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).isPresent()) {
                log.info("Payment record already exists for bookingId {}", bookingId);
                return;
            }

            Payment payment = Payment.builder()
                    .bookingId(bookingId)
                    .bookingCode(bookingCode)
                    .amount(totalAmount)
                    .status(PaymentStatus.PENDING)
                    .build();

            paymentRepository.save(payment);
            log.info("Initialized PENDING payment for bookingId {}", bookingId);

        } catch (Exception e) {
            log.error("Error processing BOOKING_CREATED event in payment-service", e);
        }
    }
}
