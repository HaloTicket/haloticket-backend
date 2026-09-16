package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.enums.OutboxStatus;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.OutboxEventRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketEventListener {

    private final TicketRepository ticketRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "haloticket.payment.completed.v1", groupId = "ticket-service")
    @Transactional
    public void handlePaymentCompleted(String message) {
        try {
            log.info("Received PAYMENT_COMPLETED event in ticket-service: {}", message);
            JsonNode node = objectMapper.readTree(message);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : null;
            String customerId = node.has("customerId") ? node.get("customerId").asText() : "ANONYMOUS";
            String eventId = node.has("eventId") ? node.get("eventId").asText() : "UNKNOWN";

            if (bookingId == null || bookingId.isBlank()) {
                log.warn("PAYMENT_COMPLETED event missing bookingId: {}", message);
                return;
            }

            // check if tickets already issued for this booking
            List<Ticket> existing = ticketRepository.findByBookingId(bookingId);
            if (!existing.isEmpty()) {
                log.info("Tickets already issued for bookingId {}", bookingId);
                return;
            }

            // create ticket
            String qrToken = generateQrToken(bookingId, customerId);

            Ticket ticket = Ticket.builder()
                    .bookingId(bookingId)
                    .ownerId(customerId)
                    .eventId(eventId)
                    .qrCodeHash(qrToken)
                    .qrVersion(1)
                    .status(TicketStatus.VALID)
                    .issuedAt(LocalDateTime.now())
                    .build();

            Ticket savedTicket = ticketRepository.save(ticket);
            log.info("Issued ticket {} for bookingId {}", savedTicket.getId(), bookingId);

            // outbox event for TICKETS_ISSUED
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("bookingId", bookingId);
            payloadMap.put("customerId", customerId);
            payloadMap.put("eventId", eventId);
            payloadMap.put("ticketId", savedTicket.getId());
            payloadMap.put("qrToken", qrToken);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("TICKET")
                    .aggregateId(savedTicket.getId())
                    .eventType("TICKETS_ISSUED")
                    .topic("haloticket.tickets.issued.v1")
                    .payload(objectMapper.writeValueAsString(payloadMap))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            outboxEventRepository.save(outboxEvent);

        } catch (Exception e) {
            log.error("Error issuing ticket for PAYMENT_COMPLETED event", e);
        }
    }

    private String generateQrToken(String bookingId, String customerId) {
        try {
            String raw = bookingId + ":" + customerId + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
