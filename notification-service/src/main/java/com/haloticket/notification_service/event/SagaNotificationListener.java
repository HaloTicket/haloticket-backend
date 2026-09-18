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
import com.haloticket.notification_service.service.NotificationService;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaNotificationListener {

    private final ObjectMapper objectMapper;
    private final NotificationEmailSender emailSender;
    private final NotificationService notificationService;

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

    @KafkaListener(topics = "haloticket.marketplace.gift.invited.v1", groupId = "notification-service-marketplace")
    public void handleGiftInvitation(String payload) {
        publishMarketplaceNotification(payload, "GIFT_INVITATION", "Lời mời tặng vé", "Bạn đã nhận được lời mời tặng vé trên HaloTicket.");
    }

    @KafkaListener(topics = "haloticket.marketplace.gift.completed.v1", groupId = "notification-service-marketplace")
    public void handleGiftCompleted(String payload) {
        publishMarketplaceNotification(payload, "GIFT_COMPLETED", "Tặng vé thành công", "Vé đã được chuyển quyền sở hữu thành công.");
    }

    @KafkaListener(topics = "haloticket.marketplace.gift.cancelled.v1", groupId = "notification-service-marketplace")
    public void handleGiftCancelled(String payload) {
        publishMarketplaceNotification(payload, "GIFT_CANCELLED", "Tặng vé đã hủy", "Lời mời tặng vé đã được hủy hoặc hết hạn.");
    }

    @KafkaListener(topics = {"haloticket.marketplace.listing.activated.v1", "haloticket.marketplace.listing.failed.v1"}, groupId = "notification-service-marketplace")
    public void handleResaleListing(String payload) {
        JsonNode node;
        try { node = objectMapper.readTree(payload); } catch (Exception exception) { throw new IllegalStateException("Invalid marketplace notification payload", exception); }
        String eventType = node.has("status") && "FAILED".equals(node.get("status").asText()) ? "RESALE_LISTING_FAILED" : "RESALE_LISTING_ACTIVATED";
        String title = "RESALE_LISTING_FAILED".equals(eventType) ? "Đăng bán vé thất bại" : "Đăng bán vé thành công";
        String message = "RESALE_LISTING_FAILED".equals(eventType) ? "Không thể đăng bán vé, hệ thống đang khôi phục trạng thái vé." : "Đăng bán vé thành công. Vé đã được đưa lên sàn.";
        publishMarketplaceNotification(node, eventType, title, message);
    }

    private void publishMarketplaceNotification(String payload, String type, String title, String message) {
        try { publishMarketplaceNotification(objectMapper.readTree(payload), type, title, message); }
        catch (Exception exception) { throw new IllegalStateException("Invalid marketplace notification payload", exception); }
    }

    private void publishMarketplaceNotification(JsonNode node, String type, String title, String message) {
        String recipient = node.hasNonNull("recipientEmail") ? node.get("recipientEmail").asText() : node.path("sellerId").asText(null);
        if (recipient == null || recipient.isBlank()) return;
        notificationService.publishDirectNotification(recipient, type, title, message);
    }
}
