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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaNotificationListener {

    private final ObjectMapper objectMapper;
    private final NotificationEmailSender emailSender;
    private final NotificationService notificationService;
    private final JavaMailSender mailSender;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${notification.gift-accept-base-url:http://localhost:5173/gifts/accept}")
    private String giftAcceptBaseUrl;
    @Value("${notification.mail.from:no-reply@haloticket.local}")
    private String mailFrom;

    private final NotificationTemplateRepository templateRepository;

    @KafkaListener(topics = "haloticket.booking.confirmed.v1", groupId = "notification-service-saga")
    public void handleBookingConfirmedNotification(String payload) {
        try {
            log.info("NotificationService received BOOKING_CONFIRMED event: {}", payload);
            JsonNode node = objectMapper.readTree(payload);
            String bookingCode = node.has("bookingCode") ? node.get("bookingCode").asText() : "";
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
                vars.put("eventId", eventId);
                
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
        try {
            JsonNode node = objectMapper.readTree(payload);
            String email = node.path("recipientEmail").asText();
            String token = node.path("invitationToken").asText();
            String sagaId = node.path("sagaId").asText();
            String recipientUserId = node.path("recipientUserId").asText();
            if (email.isBlank() || token.isBlank()) {
                log.error("Gift invitation missing email or token, skipping: {}", payload);
                return;
            }

            // 1. Save notification to DB + broadcast via STOMP (most important)
            try {
                notificationService.publishDirectNotification(recipientUserId, "GIFT_INVITATION",
                        "Lời mời tặng vé", "Bạn có lời mời nhận vé. Mã lời mời: " + sagaId);
                log.info("Gift notification saved for user {} saga {}", recipientUserId, sagaId);
            } catch (Exception notifError) {
                log.error("Failed to save gift notification for saga {}", sagaId, notifError);
            }

            // 2. Send beautiful HTML email (best-effort, failure does not block notification)
            try {
                String link = giftAcceptBaseUrl + "?sagaId=" + URLEncoder.encode(sagaId, StandardCharsets.UTF_8)
                        + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

                String senderName = node.path("senderName").asText("Một người bạn trên HaloTicket");
                if (senderName.isBlank()) senderName = "Một người bạn trên HaloTicket";

                String ticketTypeName = node.path("ticketTypeName").asText("Vé sự kiện");
                if (ticketTypeName.isBlank()) ticketTypeName = "Vé sự kiện";

                String eventTitle = node.path("eventTitle").asText("Sự kiện HaloTicket");
                if (eventTitle.isBlank()) eventTitle = "Sự kiện trên HaloTicket";

                String section = node.path("section").asText("");
                String row = node.path("row").asText("");
                String seat = node.path("seat").asText("");
                StringBuilder seatBuilder = new StringBuilder();
                if (!section.isBlank()) seatBuilder.append("Khu vực ").append(section);
                if (!row.isBlank()) {
                    if (seatBuilder.length() > 0) seatBuilder.append(" - ");
                    seatBuilder.append("Hàng ").append(row);
                }
                if (!seat.isBlank()) {
                    if (seatBuilder.length() > 0) seatBuilder.append(" - ");
                    seatBuilder.append("Ghế ").append(seat);
                }
                String seatInfo = seatBuilder.length() > 0 ? seatBuilder.toString() : "Ghế tự do";

                String giftMessage = node.path("message").asText("");
                String messageBlock = "";
                if (!giftMessage.isBlank()) {
                    messageBlock = "<div class=\"message-box\"><span class=\"message-label\">Lời nhắn</span><p class=\"message-text\">\""
                            + giftMessage.replace("<", "&lt;").replace(">", "&gt;") + "\"</p></div>";
                }

                // Format expiresAt nicely (e.g. 15:30 ngày 20/09/2026)
                String expiresAtRaw = node.path("expiresAt").asText("");
                String expiresAtFormatted = expiresAtRaw;
                try {
                    Instant instant = Instant.parse(expiresAtRaw);
                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
                    expiresAtFormatted = dtf.format(instant);
                } catch (Exception ignored) {}

                Map<String, String> vars = new HashMap<>();
                vars.put("senderName", senderName);
                vars.put("recipientEmail", email);
                vars.put("eventTitle", eventTitle);
                vars.put("ticketTypeName", ticketTypeName);
                vars.put("seatInfo", seatInfo);
                vars.put("messageBlock", messageBlock);
                vars.put("expiresAtFormatted", expiresAtFormatted);
                vars.put("acceptUrl", link);

                String html = templateRenderer.render("gift-invitation.html", vars);

                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
                helper.setFrom(mailFrom);
                helper.setTo(email);
                helper.setSubject("🎁 Bạn có lời mời nhận vé từ " + senderName + " trên HaloTicket");
                helper.setText(html, true);

                mailSender.send(mimeMessage);
                log.info("Gift invitation HTML email sent to {} for saga {}", email, sagaId);
            } catch (Exception emailError) {
                log.error("Gift invitation email failed for saga {}", sagaId, emailError);
            }
        } catch (Exception exception) {
            // Do NOT throw — let Kafka commit the offset to avoid infinite retry loop
            log.error("Could not process gift invitation event, skipping", exception);
        }
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
