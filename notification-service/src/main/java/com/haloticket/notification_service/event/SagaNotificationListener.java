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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaNotificationListener {

    private final ObjectMapper objectMapper;
    private final NotificationEmailSender emailSender;

    private final NotificationTemplateRepository templateRepository;
    private final JavaMailSender mailSender;
    private final EmailTemplateRenderer templateRenderer;
    
    @Value("${notification.mail.from:no-reply@haloticket.local}")
    private String fromAddress;

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
                vars.put("customerId", customerId);
                
                String eventId = node.has("eventId") ? node.get("eventId").asText() : "";
                vars.put("eventId", eventId); // just in case template uses it
                
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

    @KafkaListener(topics = "notification.event.member.invitation", groupId = "notification-service-saga")
    public void onEventMemberInvitation(String payloadStr) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);
            
            String email = (String) payload.get("email");
            if (email == null) return;
            
            // build variables map for EmailTemplateRenderer
            Map<String, String> variables = new HashMap<>();
            variables.put("name", String.valueOf(payload.get("name")));
            variables.put("eventTitle", String.valueOf(payload.get("eventTitle")));
            variables.put("eventId", String.valueOf(payload.get("eventId")));
            
            String role = (String) payload.get("role");
            String roleDisplayName = role;
            if ("OWNER".equals(role)) roleDisplayName = "Chủ sự kiện";
            else if ("ADMIN".equals(role)) roleDisplayName = "Quản trị viên";
            else if ("MANAGER".equals(role)) roleDisplayName = "Quản lý";
            else if ("CHECKIN_STAFF".equals(role)) roleDisplayName = "Nhân viên Check-in";
            variables.put("role", roleDisplayName);
            
            Boolean isNewUser = (Boolean) payload.get("isNewUser");
            if (isNewUser != null && isNewUser) {
                String generatedPassword = String.valueOf(payload.get("generatedPassword"));
                String newUserContent = "<div style=\"margin-top:20px;padding:16px;background:#f8fafc;border-radius:8px;border:1px solid #e3eaf4;\">\n" +
                        "    <p style=\"margin-top:0;\">Hệ thống đã tự động tạo tài khoản cho bạn để có thể tham gia quản lý sự kiện này.</p>\n" +
                        "    <p style=\"margin-bottom:0;\"><strong>Mật khẩu đăng nhập tạm thời:</strong> <span style=\"font-family:monospace;font-size:16px;background:#e3eaf4;padding:2px 6px;border-radius:4px;\">" + generatedPassword + "</span></p>\n" +
                        "    <p style=\"font-size:12px;color:#667085;margin-bottom:0;\">(Vui lòng đăng nhập và đổi mật khẩu sau khi truy cập)</p>\n" +
                        "</div>";
                variables.put("newUserContent", newUserContent);
            } else {
                variables.put("newUserContent", "");
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
            log.error("Failed to process event member invitation: {}", e.getMessage(), e);
        }
    }
}
