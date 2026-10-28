package com.haloticket.notification_service.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationStatus;
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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
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
    private final NotificationTemplateRepository templateRepository;

    @Value("${notification.gift-accept-base-url:http://localhost:5173/gifts/accept}")
    private String giftAcceptBaseUrl;
    @Value("${notification.mail.from:no-reply@haloticket.local}")
    private String mailFrom;
    @Value("${notification.mail.from:no-reply@haloticket.local}")
    private String fromAddress;
    @Value("${notification.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @KafkaListener(topics = "haloticket.tickets.issued.v1", groupId = "notification-service-saga")
    public void handleTicketsIssuedNotification(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String bookingId = node.path("bookingId").asText("");
            String bookingCode = node.path("bookingCode").asText("");
            String customerEmail = node.path("customerEmail").asText("");
            JsonNode tickets = node.path("tickets");
            if (bookingId.isBlank() || bookingCode.isBlank() || customerEmail.isBlank()
                    || !tickets.isArray() || tickets.isEmpty()) {
                log.warn("Skipping incomplete TICKETS_ISSUED batch for booking {}", bookingId);
                return;
            }

            NotificationTemplate template = templateRepository.findByTypeAndChannelAndStatus(
                    NotificationType.TICKET_CONFIRMATION, NotificationChannel.EMAIL, NotificationTemplateStatus.ACTIVE)
                    .orElseThrow(() -> new IllegalStateException("Active ticket confirmation template is missing"));
            Map<String, String> vars = orderVariables(node, tickets);
            NotificationEvent event = new NotificationEvent("order-confirmation:" + bookingId,
                    template.getId(), node.path("customerId").asText(""), customerEmail,
                    NotificationChannel.EMAIL, vars, null);
            var notification = notificationService.processEvent(event);
            if (notification.getStatus() == NotificationStatus.SENT) return;

            emailSender.send(template, customerEmail, vars);
            notificationService.markAsSent(notification.getId());
            log.info("Sent order confirmation email for booking {}", bookingId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process TICKETS_ISSUED notification", e);
        }
    }

    private Map<String, String> orderVariables(JsonNode node, JsonNode tickets) {
        Map<String, String> vars = new HashMap<>();
        String name = node.path("customerName").asText("");
        String bookingCode = node.path("bookingCode").asText();
        String eventTitle = node.path("eventTitle").asText("");
        if (eventTitle.isBlank()) eventTitle = "Sự kiện " + node.path("eventId").asText("");
        vars.put("fullName", name.isBlank() ? "Quý khách" : name);
        vars.put("bookingCode", bookingCode);
        vars.put("eventTitle", eventTitle);
        vars.put("ticketCount", tickets.size() + " vé");
        vars.put("amount", formatAmount(node.path("amount")));
        vars.put("orderUrl", frontendBaseUrl.replaceAll("/+$", "")
                + "/my-tickets/orders/" + URLEncoder.encode(bookingCode, StandardCharsets.UTF_8).replace("+", "%20"));

        StringBuilder rows = new StringBuilder();
        for (JsonNode ticket : tickets) {
            String type = ticket.path("ticketTypeName").asText("");
            if (type.isBlank()) type = "Vé sự kiện";
            StringBuilder seat = new StringBuilder();
            appendSeatPart(seat, "Khu ", ticket.path("section").asText(""));
            appendSeatPart(seat, "Hàng ", ticket.path("row").asText(""));
            appendSeatPart(seat, "Ghế ", ticket.path("seat").asText(""));
            rows.append("<tr><td>").append(escapeHtml(type)).append("</td><td>")
                    .append(seat.isEmpty() ? "Vé tự do" : seat)
                    .append("</td></tr>");
        }
        vars.put("ticketRows", rows.toString());
        return vars;
    }

    private static void appendSeatPart(StringBuilder seat, String label, String value) {
        if (value.isBlank()) return;
        if (!seat.isEmpty()) seat.append(" · ");
        seat.append(label).append(escapeHtml(value));
    }

    private static String formatAmount(JsonNode amount) {
        if (!amount.isNumber()) return "—";
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN"));
        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        return format.format(amount.decimalValue()) + " ₫";
    }

    private static String escapeHtml(String value) {
        return EmailTemplateRenderer.escape(value);
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
                // the payload holds the invitation token; log only which saga it belongs to
                log.error("Gift invitation missing email or token, skipping saga {}", sagaId);
                return;
            }

            // 1. save notification to DB + broadcast via STOMP (most important)
            try {
                notificationService.publishDirectNotification(recipientUserId, "GIFT_INVITATION",
                        "Lời mời tặng vé", "Bạn có lời mời nhận vé. Mã lời mời: " + sagaId);
                log.info("Gift notification saved for user {} saga {}", recipientUserId, sagaId);
            } catch (Exception notifError) {
                log.error("Failed to save gift notification for saga {}", sagaId, notifError);
            }

            // 2. send beautiful HTML email (best-effort, failure does not block notification)
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
                            + escapeHtml(giftMessage) + "\"</p></div>";
                }

                // format expiresAt nicely (e.g. 15:30 ngày 20/09/2026)
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
                helper.setSubject("Bạn có lời mời nhận vé từ " + senderName + " trên HaloTicket");
                helper.setText(html, true);

                mailSender.send(mimeMessage);
                log.info("Gift invitation HTML email sent to {} for saga {}", email, sagaId);
            } catch (Exception emailError) {
                log.error("Gift invitation email failed for saga {}", sagaId, emailError);
            }
        } catch (Exception exception) {
            log.error("Could not process gift invitation event, skipping ({})", exception.getClass().getSimpleName());
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

    @KafkaListener(topics = "notification.event.member.invitation", groupId = "notification-service-saga")
    public void onEventMemberInvitation(String payloadStr) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);

            String email = (String) payload.get("email");
            if (email == null) return;

            String base = frontendBaseUrl.replaceAll("/+$", "");
            String eventId = urlSegment(String.valueOf(payload.get("eventId")));
            String token = payload.get("invitationToken") instanceof String value && !value.isBlank() ? value : null;
            String setPasswordUrl = base + "/forgot-password";

            // everything a person typed (names, event title) goes into the mail escaped, never as markup
            Map<String, String> variables = new HashMap<>();
            variables.put("name", String.valueOf(payload.get("name")));
            variables.put("eventTitle", String.valueOf(payload.get("eventTitle")));

            String role = (String) payload.get("role");
            String roleDisplayName = role;
            if ("OWNER".equals(role)) roleDisplayName = "Chủ sự kiện";
            else if ("ADMIN".equals(role)) roleDisplayName = "Quản trị viên";
            else if ("MANAGER".equals(role)) roleDisplayName = "Quản lý";
            else if ("CHECKIN_STAFF".equals(role)) roleDisplayName = "Nhân viên Check-in";
            variables.put("role", String.valueOf(roleDisplayName));

            // no password is ever sent: a person without a password sets one with the code mailed by "forgot password"
            boolean isNewUser = Boolean.TRUE.equals(payload.get("isNewUser"));
            String setPasswordBlock = "<div style=\"margin-top:20px;padding:16px;background:#f8fafc;border-radius:8px;border:1px solid #e3eaf4;\">\n" +
                    "    <p style=\"margin:0 0 8px;\">Chưa có mật khẩu? Hãy thiết lập mật khẩu cho tài khoản này: chúng tôi sẽ gửi mã xác nhận đến chính email này.</p>\n" +
                    "    <p style=\"margin:0;\"><a href=\"" + escapeHtml(setPasswordUrl) + "\" style=\"color:#0b6bcb;font-weight:bold;\">Thiết lập mật khẩu</a></p>\n" +
                    "</div>";
            String newUserContent = isNewUser
                    ? "<p style=\"margin-top:20px;\">Hệ thống đã tạo tài khoản HaloTicket cho bạn để có thể tham gia quản lý sự kiện này.</p>"
                    : "";
            variables.put("newUserContent", newUserContent);
            variables.put("setPasswordBlock", token != null || isNewUser ? setPasswordBlock : "");

            if (token != null) {
                variables.put("primaryUrl", base + "/event-invitations/accept#token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
                variables.put("primaryLabel", "Xem và trả lời lời mời");
                variables.put("expiryNote", "Lời mời có hiệu lực trong thời gian có hạn và chỉ dùng được một lần, bằng đúng tài khoản có email này.");
            } else {
                variables.put("primaryUrl", base + "/organizer/events/" + eventId + "/manage");
                variables.put("primaryLabel", "Đến trang quản trị sự kiện");
                variables.put("expiryNote", "");
            }

            // process template
            String htmlContent = templateRenderer.render("event-member-invitation.html", variables);

            // send email
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("Lời mời tham gia Ban tổ chức sự kiện - HaloTicket");
            helper.setText(htmlContent, true);

            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to process event member invitation ({})", e.getClass().getSimpleName());
        }
    }

    private static String urlSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @KafkaListener(topics = {
            "haloticket.payout.requested.v1",
            "haloticket.payout.approved.v1",
            "haloticket.direct.notification.v1",
            "haloticket.event.approved.v1",
            "haloticket.event.rejected.v1"
    }, groupId = "notification-service-payout")
    public void handleDirectAndPayoutNotifications(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String recipientUserId = node.hasNonNull("recipientUserId") && !node.path("recipientUserId").asText().isBlank()
                    ? node.path("recipientUserId").asText()
                    : node.path("userId").asText();
            String type = node.path("type").asText();
            String title = node.path("title").asText();
            String message = node.path("message").asText();

            if (!recipientUserId.isBlank() && !type.isBlank()) {
                notificationService.publishDirectNotification(recipientUserId, type, title, message);
                log.info("Sent direct/payout notification to {} (type: {})", recipientUserId, type);
            }
        } catch (Exception e) {
            log.error("Failed to process direct/payout notification: {}", e.getMessage(), e);
        }
    }
}
