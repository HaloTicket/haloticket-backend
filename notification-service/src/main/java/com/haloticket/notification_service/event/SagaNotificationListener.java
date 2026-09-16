package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaNotificationListener {

    private final ObjectMapper objectMapper;
    private final NotificationEmailSender emailSender;

    @KafkaListener(topics = "haloticket.booking.created.v1", groupId = "notification-service-saga")
    public void handleBookingCreatedNotification(String payload) {
        try {
            log.info("NotificationService received BOOKING_CREATED event: {}", payload);
            JsonNode node = objectMapper.readTree(payload);
            String bookingCode = node.has("bookingCode") ? node.get("bookingCode").asText() : "";
            String customerId = node.has("customerId") ? node.get("customerId").asText() : "";

            log.info("Order created notification logged for bookingCode: {}, customer: {}", bookingCode, customerId);
        } catch (Exception e) {
            log.error("Failed to process BOOKING_CREATED notification", e);
        }
    }

    @KafkaListener(topics = "haloticket.tickets.issued.v1", groupId = "notification-service-saga")
    public void handleTicketsIssuedNotification(String payload) {
        try {
            log.info("NotificationService received TICKETS_ISSUED event: {}", payload);
            JsonNode node = objectMapper.readTree(payload);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : "";
            String qrToken = node.has("qrToken") ? node.get("qrToken").asText() : "";

            log.info("Ticket issued notification sent for bookingId: {}, QR Token: {}", bookingId, qrToken);
        } catch (Exception e) {
            log.error("Failed to process TICKETS_ISSUED notification", e);
        }
    }
}
