package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.*;
import com.haloticket.ticket_service.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final SeatLayoutRepository seatLayoutRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "haloticket.booking.confirmed.v1", groupId = "ticket-service")
    @Transactional
    public void handleBookingConfirmed(String message) {
        try {
            issueBooking(objectMapper.readTree(message));
        } catch (Exception e) {
            log.error("Failed to issue tickets for BOOKING_CONFIRMED", e);
            throw new IllegalStateException("Ticket issuance failed", e);
        }
    }

    private void issueBooking(JsonNode booking) throws Exception {
        String bookingId = requiredText(booking, "bookingId");
        String customerId = requiredText(booking, "customerId");
        String eventId = requiredText(booking, "eventId");
        String showtimeId = text(booking, "showtimeId");

        // Concurrent Kafka deliveries must check for prior issuance under the same DB lock.
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:bookingId, 0))")
                .setParameter("bookingId", bookingId).getSingleResult();
        List<TicketType> showtimeTypes = showtimeId == null ? List.of() : ticketTypeRepository.findByShowtimeId(showtimeId);
        if (showtimeId != null && showtimeTypes.isEmpty()) {
            throw new IllegalArgumentException("No ticket types for showtime: " + showtimeId);
        }
        JsonNode items = booking.path("items");
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("Booking has no ticket items");
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, TicketType> types = new HashMap<>();
        Map<String, BigDecimal> prices = new HashMap<>();
        int purchased = 0;
        for (JsonNode item : items) {
            TicketType type = resolveType(requiredText(item, "ticketTypeId"), showtimeTypes, eventId);
            int quantity = item.path("quantity").asInt(0);
            if (quantity <= 0) throw new IllegalArgumentException("Invalid ticket quantity");
            purchased = Math.addExact(purchased, quantity);
            remaining.merge(type.getId(), quantity, Integer::sum);
            types.put(type.getId(), type);
            if (item.hasNonNull("unitPrice")) prices.put(type.getId(), item.get("unitPrice").decimalValue());
        }
        List<Ticket> existing = ticketRepository.findByBookingId(bookingId);
        if (!existing.isEmpty()) {
            if (existing.size() != purchased) {
                throw new IllegalStateException("Existing ticket count differs from purchased quantity for booking " + bookingId);
            }
            log.info("Tickets already issued for bookingId {}", bookingId);
            return;
        }

        List<String> seatIds = new ArrayList<>();
        if (booking.path("seatIds").isArray()) {
            for (JsonNode seatId : booking.get("seatIds")) seatIds.add(seatId.asText());
        }
        if (seatIds.size() > purchased || new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("Seat count exceeds purchased quantity or contains duplicates");
        }
        JsonNode layout = null;
        if (!seatIds.isEmpty()) {
            layout = seatLayoutRepository.findFirstByEventIdAndStatusOrderByUpdatedAtDesc(eventId, SeatLayoutStatus.PUBLISHED)
                    .map(SeatLayout::getDraftJson).filter(s -> !s.isBlank())
                    .map(s -> {
                        try { return objectMapper.readTree(s); }
                        catch (Exception e) { throw new IllegalArgumentException("Invalid seat layout JSON", e); }
                    }).orElse(null);
        }

        List<Map<String, Object>> details = new ArrayList<>();
        for (String seatId : seatIds) {
            Seat seat = seatRepository.findById(seatId).orElse(null);
            JsonNode mapSeat = findLayoutSeat(layout, seatId);
            boolean ga = "GENERAL_ADMISSION".equals(seatId) || seatId.contains("-GA-");
            if (seat == null && mapSeat == null && !ga) throw new IllegalArgumentException("Seat not found in layout: " + seatId);

            String section = null, row = null, number = null, mappedTypeId = null;
            if (seat != null) {
                if (seat.getSeatRow() == null || seat.getSeatRow().getSeatZone() == null) {
                    throw new IllegalArgumentException("Seat is missing its zone: " + seatId);
                }
                SeatRow seatRow = seat.getSeatRow();
                section = seatRow.getSeatZone().getName();
                row = seatRow.getLabel();
                number = seat.getSeatNumber();
                mappedTypeId = seatRow.getSeatZone().getTicketTypeId();
            } else if (mapSeat != null) {
                String zoneId = text(mapSeat, "zoneId");
                JsonNode zone = zoneId == null || layout == null ? null : layout.path("zones").path(zoneId);
                section = zone == null ? null : text(zone, "name");
                row = text(mapSeat, "row");
                number = text(mapSeat, "number");
                mappedTypeId = text(mapSeat, "tierId");
                if (mappedTypeId == null && zone != null) mappedTypeId = text(zone, "tierId");
            }

            TicketType type;
            if (mappedTypeId != null) {
                type = resolveType(mappedTypeId, showtimeTypes, eventId);
                if (remaining.getOrDefault(type.getId(), 0) == 0) {
                    throw new IllegalArgumentException("Seat ticket type differs from purchased items: " + seatId);
                }
            } else if (remaining.size() == 1) {
                type = types.values().iterator().next();
            } else if (ga) {
                type = remaining.entrySet().stream().filter(e -> e.getValue() > 0)
                        .map(e -> types.get(e.getKey())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No purchased type for GA seat"));
            } else {
                throw new IllegalArgumentException("Seat has no ticket type: " + seatId);
            }
            if (remaining.getOrDefault(type.getId(), 0) <= 0) {
                throw new IllegalArgumentException("Seat count exceeds purchased quantity for type " + type.getId());
            }
            remaining.put(type.getId(), remaining.get(type.getId()) - 1);
            details.add(issueSingleTicket(bookingId, customerId, eventId, showtimeId, type,
                    prices.get(type.getId()), seat, seatId, section, row, number));
        }
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            TicketType type = types.get(entry.getKey());
            for (int i = 0; i < entry.getValue(); i++) {
                details.add(issueSingleTicket(bookingId, customerId, eventId, showtimeId, type,
                        prices.get(type.getId()), null, "GENERAL_ADMISSION_" + type.getId() + "_" + i,
                        null, null, null));
            }
        }
        if (details.size() != purchased) throw new IllegalStateException("Issued count does not match purchased count");

        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("bookingId", "bookingCode", "customerId", "customerEmail", "customerName",
                "eventId", "showtimeId", "eventTitle", "eventStartTime", "locationName", "address", "amount")) {
            if (booking.hasNonNull(field)) payload.put(field, objectMapper.treeToValue(booking.get(field), Object.class));
        }
        payload.put("tickets", details);
        outboxEventRepository.save(OutboxEvent.builder().aggregateType("BOOKING").aggregateId(bookingId)
                .eventType("TICKETS_ISSUED").topic("haloticket.tickets.issued.v1")
                .payload(objectMapper.writeValueAsString(payload)).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    private TicketType resolveType(String typeId, List<TicketType> showtimeTypes, String eventId) {
        TicketType requested = ticketTypeRepository.findById(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket type: " + typeId));
        if (!eventId.equals(requested.getEventId())) throw new IllegalArgumentException("Ticket type belongs to another event");
        if (showtimeTypes.isEmpty()) return requested;
        return showtimeTypes.stream().filter(t -> t.getId().equals(typeId)).findFirst()
                .or(() -> showtimeTypes.stream().filter(t -> t.getName().trim().equalsIgnoreCase(requested.getName().trim())).findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Ticket type unavailable for showtime: " + typeId));
    }

    private JsonNode findLayoutSeat(JsonNode layout, String seatId) {
        if (layout == null || !layout.path("seats").isArray()) return null;
        for (JsonNode seat : layout.get("seats")) {
            if (seatId.equals(text(seat, "id"))) return seat;
        }
        return null;
    }

    private Map<String, Object> issueSingleTicket(String bookingId, String customerId, String eventId,
            String showtimeId, TicketType type, BigDecimal price, Seat seat, String seatId,
            String section, String row, String number) {
        String qrToken = generateQrToken(bookingId, customerId, seatId);
        Ticket ticket = ticketRepository.save(Ticket.builder().bookingId(bookingId).ownerId(customerId)
                .eventId(eventId).showtimeId(showtimeId).ticketType(type).seat(seat)
                .seatReference(seatId).seatSection(section).seatRowLabel(row).seatNumberLabel(number)
                .originalPrice(BigDecimal.valueOf(type.getPrice())).purchasePrice(price)
                .acquisitionType("PRIMARY").qrCodeHash(qrToken).qrVersion(1)
                .status(TicketStatus.VALID).issuedAt(LocalDateTime.now()).build());
        if (seat != null) {
            seat.setSeatStatus(SeatStatus.SOLD);
            seatRepository.save(seat);
        }
        if (type.getTotalQuantity() != null) {
            type.setTotalQuantity(Math.max(0, type.getTotalQuantity() - 1));
            ticketTypeRepository.save(type);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ticketId", ticket.getId());
        detail.put("ticketTypeId", type.getId());
        detail.put("ticketTypeName", type.getName());
        detail.put("seatId", seatId);
        detail.put("section", section);
        detail.put("row", row);
        detail.put("seat", number);
        detail.put("price", price == null ? type.getPrice() : price);
        return detail;
    }

    private String requiredText(JsonNode node, String key) {
        String value = text(node, key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private String generateQrToken(String bookingId, String customerId, String seatId) {
        try {
            String raw = bookingId + ":" + customerId + ":" + seatId + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
