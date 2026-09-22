package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.enums.OutboxStatus;
import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.entity.Seat;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.OutboxEventRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketEventListener {

    private final TicketRepository ticketRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatRepository seatRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "haloticket.booking.confirmed.v1", groupId = "ticket-service")
    @Transactional
    public void handleBookingConfirmed(String message) {
        try {
            log.info("Received BOOKING_CONFIRMED event in ticket-service: {}", message);
            JsonNode node = objectMapper.readTree(message);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : null;
            String customerId = node.has("customerId") ? node.get("customerId").asText() : "ANONYMOUS";
            String eventId = node.has("eventId") ? node.get("eventId").asText() : "UNKNOWN";

            if (bookingId == null || bookingId.isBlank()) {
                log.warn("BOOKING_CONFIRMED event missing bookingId: {}", message);
                return;
            }

            // check if tickets already issued for this booking
            List<Ticket> existing = ticketRepository.findByBookingId(bookingId);
            if (!existing.isEmpty()) {
                log.info("Tickets already issued for bookingId {}", bookingId);
                return;
            }

            // fetch default ticket type for event
            TicketType defaultType = ticketTypeRepository.findByEventId(eventId).stream().findFirst().orElse(null);

            List<String> seatIds = new ArrayList<>();
            if (node.has("seatIds") && node.get("seatIds").isArray()) {
                for (JsonNode sn : node.get("seatIds")) {
                    seatIds.add(sn.asText());
                }
            }

            Map<String, Integer> ticketTypeQuantities = new HashMap<>();
            if (node.has("items") && node.get("items").isArray()) {
                for (JsonNode itemNode : node.get("items")) {
                    String tType = itemNode.has("ticketTypeId") && !itemNode.get("ticketTypeId").isNull() ? itemNode.get("ticketTypeId").asText() : null;
                    int qty = itemNode.has("quantity") ? itemNode.get("quantity").asInt() : 1;
                    if (tType != null) {
                        ticketTypeQuantities.put(tType, ticketTypeQuantities.getOrDefault(tType, 0) + qty);
                    }
                }
            } else if (seatIds.isEmpty()) {
                seatIds.add("GENERAL_ADMISSION"); // fallback for 1 ticket
            }

            for (String seatId : seatIds) {
                if ("GENERAL_ADMISSION".equals(seatId)) {
                    issueSingleTicket(bookingId, customerId, eventId, defaultType, null, seatId);
                    continue;
                }

                Seat seat = seatRepository.findById(seatId).orElse(null);
                TicketType actualType = defaultType;

                if (seat != null && seat.getSeatRow() != null && seat.getSeatRow().getSeatZone() != null) {
                    String ticketTypeId = seat.getSeatRow().getSeatZone().getTicketTypeId();
                    if (ticketTypeId != null) {
                        actualType = ticketTypeRepository.findById(ticketTypeId).orElse(defaultType);
                    }
                }

                issueSingleTicket(bookingId, customerId, eventId, actualType, seat, seatId);

                if (actualType != null && ticketTypeQuantities.containsKey(actualType.getId())) {
                    ticketTypeQuantities.put(actualType.getId(), ticketTypeQuantities.get(actualType.getId()) - 1);
                }
            }

            for (Map.Entry<String, Integer> entry : ticketTypeQuantities.entrySet()) {
                String typeId = entry.getKey();
                int remainingQty = entry.getValue();
                if (remainingQty > 0) {
                    TicketType type = ticketTypeRepository.findById(typeId).orElse(defaultType);
                    for (int i = 0; i < remainingQty; i++) {
                        issueSingleTicket(bookingId, customerId, eventId, type, null, "GENERAL_ADMISSION_" + typeId + "_" + i);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error issuing ticket for PAYMENT_COMPLETED event", e);
        }
    }

    private void issueSingleTicket(String bookingId, String customerId, String eventId, TicketType type, Seat seat, String seatIdRef) {
        String qrToken = generateQrToken(bookingId, customerId, seatIdRef);
        Ticket ticket = Ticket.builder()
                .bookingId(bookingId)
                .ownerId(customerId)
                .eventId(eventId)
                .ticketType(type)
                .seat(seat)
                .qrCodeHash(qrToken)
                .qrVersion(1)
                .status(TicketStatus.VALID)
                .issuedAt(LocalDateTime.now())
                .build();
        Ticket savedTicket = ticketRepository.save(ticket);
        log.info("Issued ticket {} for bookingId {}, seat {}", savedTicket.getId(), bookingId, seatIdRef);

        if (seat != null) {
            seat.setSeatStatus(SeatStatus.SOLD);
            seatRepository.save(seat);
        }

        if (type != null) {
            type.setTotalQuantity(Math.max(0, type.getTotalQuantity() - 1));
            ticketTypeRepository.save(type);
        }

        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("bookingId", bookingId);
            payloadMap.put("customerId", customerId);
            payloadMap.put("eventId", eventId);
            payloadMap.put("ticketId", savedTicket.getId());
            payloadMap.put("seatId", seatIdRef);
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
            log.error("Failed to write outbox event TICKETS_ISSUED", e);
        }
    }

    private String generateQrToken(String bookingId, String customerId, String seatId) {
        try {
            String raw = bookingId + ":" + customerId + ":" + seatId + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
