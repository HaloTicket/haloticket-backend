package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaNotificationListener {

    private final ObjectMapper objectMapper;
    private final NotificationEmailSender emailSender;

    private final NotificationTemplateRepository templateRepository;

    @KafkaListener(topics = "haloticket.booking.confirmed.v1", groupId = "notification-service-saga")
    public void handleBookingConfirmedNotification(String payload) {
        try {
            log.info("NotificationService received BOOKING_CONFIRMED event: {}", payload);
            JsonNode node = objectMapper.readTree(payload);
            String bookingCode = node.has("bookingCode") ? node.get("bookingCode").asText() : "";
            String customerId = node.has("customerId") ? node.get("customerId").asText() : "";
            String customerEmail = node.has("customerEmail") ? node.get("customerEmail").asText() : "";
            String customerName = node.has("customerName") ? node.get("customerName").asText() : "";
            
            if (customerEmail == null || customerEmail.isBlank()) {
                log.warn("Cannot send confirmation email, missing customerEmail for booking: {}", bookingCode);
                return;
            }

            NotificationTemplate template = templateRepository.findByTypeAndChannelAndStatus(
                    NotificationType.TICKET_CONFIRMATION,
                    NotificationChannel.EMAIL,
                    NotificationTemplateStatus.ACTIVE
            ).orElse(null);

            if (template != null) {
                Map<String, String> vars = new HashMap<>();
                vars.put("fullName", customerName != null && !customerName.isBlank() ? customerName : "Quý khách");
                vars.put("bookingCode", bookingCode);
                
                String eventId = node.has("eventId") ? node.get("eventId").asText() : "";
                vars.put("eventId", eventId); // Just in case template uses it
                
                emailSender.send(template, customerEmail, vars);
                log.info("Sent order confirmation email to {} for booking {}", customerEmail, bookingCode);
            } else {
                log.warn("TICKET_CONFIRMATION template not found in DB!");
            }

        } catch (Exception e) {
            log.error("Failed to process BOOKING_CONFIRMED notification", e);
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
