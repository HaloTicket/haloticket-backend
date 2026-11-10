package com.haloticket.booking_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.dto.response.EventShowtimeDto;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Asks ticket-service to issue the tickets of a paid order again. Issuance is idempotent (one issue key per ticket), so asking
 * twice never makes duplicates. Used by the order owner ("Tải lại" on the order page) and by the scheduler that re-drives paid
 * orders still missing tickets; the admin button has its own endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingReissueService {

    public static final String TOPIC = "haloticket.booking.confirmed.v1";
    /** Asking again sooner than this answers TOO_SOON: an order already being re-issued is not flooded with messages. */
    public static final int MIN_SECONDS_BETWEEN_REQUESTS = 30;

    public enum Result { REQUESTED, ALREADY_ISSUED, TOO_SOON, NOT_PAID }

    public record Outcome(Result result, Long retryAfterSeconds) {
        static Outcome of(Result result) {
            return new Outcome(result, null);
        }
    }

    private final BookingRepository bookings;
    private final SeatReservationRepository seatReservations;
    private final OutboxEventRepository outbox;
    private final EventServiceClient events;
    private final TicketStockClient ticketStock;
    private final GaReservationProperties ticketProperties;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    /** The order owner's request. Someone else's order answers 404, like the order page, so codes cannot be probed. */
    @Transactional
    public Outcome reissueForCustomer(String bookingCode, String customerId) {
        Booking booking = bookings.findByBookingCode(bookingCode)
                .or(() -> bookings.findById(bookingCode))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        if (booking.getCustomerId() == null || !booking.getCustomerId().equals(customerId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        }
        return reissue(booking);
    }

    @Transactional
    public Outcome reissue(Booking booking) {
        if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.CONFIRMED) {
            return Outcome.of(Result.NOT_PAID);
        }
        long expected = expectedTickets(booking);
        Long issued = issuedCount(booking.getId());
        if (issued != null && expected > 0 && issued >= expected) {
            return Outcome.of(Result.ALREADY_ISSUED);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = booking.getLastReissueAt();
        if (last != null) {
            long since = Duration.between(last, now).getSeconds();
            if (since < MIN_SECONDS_BETWEEN_REQUESTS) {
                return new Outcome(Result.TOO_SOON, MIN_SECONDS_BETWEEN_REQUESTS - since);
            }
        }
        try {
            kafka.send(TOPIC, booking.getId(), objectMapper.writeValueAsString(payloadFor(booking)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish the booking confirmation again: " + e.getClass().getSimpleName(), e);
        }
        bookings.markReissueRequested(booking.getId(), now);
        log.info("[REISSUE] requested bookingId={}", booking.getId());
        return Outcome.of(Result.REQUESTED);
    }

    static long expectedTickets(Booking booking) {
        if (booking.getItems() == null) return 0;
        return booking.getItems().stream().mapToLong(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
    }

    /** Tickets issued for the booking, or null when ticket-service cannot answer (then the request goes out anyway). */
    private Long issuedCount(String bookingId) {
        String token = ticketProperties.getInternalToken();
        if (token == null || token.isBlank()) return null;
        try {
            Map<String, Map<String, Integer>> answer = ticketStock.issuedCounts(token, Map.of("bookingIds", List.of(bookingId)));
            if (answer == null || answer.get("counts") == null) return null;
            Integer n = answer.get("counts").get(bookingId);
            return n == null ? 0L : n.longValue();
        } catch (RuntimeException e) {
            log.warn("[REISSUE] issued count unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The confirmation message as it was first published, else rebuilt from the booking. */
    Map<String, Object> payloadFor(Booking booking) {
        Optional<OutboxEvent> original = outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(booking.getId(), "BOOKING_CONFIRMED");
        if (original.isPresent() && original.get().getPayload() != null) {
            try {
                return objectMapper.readValue(original.get().getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("[REISSUE] original payload unreadable, rebuilding: {}", e.getClass().getSimpleName());
            }
        }
        return rebuild(booking);
    }

    private Map<String, Object> rebuild(Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("bookingCode", booking.getBookingCode());
        payload.put("customerId", booking.getCustomerId());
        payload.put("customerEmail", booking.getCustomerEmail());
        payload.put("customerName", booking.getCustomerName());
        payload.put("eventId", booking.getEventId());
        payload.put("showtimeId", booking.getShowtimeId());
        payload.put("amount", booking.getTotalAmount());
        payload.put("seatIds", seatReservations.findByBooking(booking).stream().map(SeatReservation::getSeatId).filter(Objects::nonNull).toList());
        try {
            EventResponse event = events.getEventById(booking.getEventId());
            if (event != null) {
                payload.put("eventTitle", event.getTitle());
                payload.put("locationName", event.getLocationName());
                payload.put("address", event.getAddress());
                EventShowtimeDto showtime = event.getShowtimes() == null ? null : event.getShowtimes().stream()
                        .filter(st -> Objects.equals(st.getId(), booking.getShowtimeId())).findFirst().orElse(null);
                var start = showtime != null ? showtime.getStartTime() : event.getStartTime();
                if (start != null) payload.put("eventStartTime", start.toString());
            }
        } catch (Exception e) {
            log.warn("[REISSUE] event details unavailable for booking {}", booking.getId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (booking.getItems() != null) {
            for (BookingItem item : booking.getItems()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticketTypeId", item.getTicketTypeId());
                row.put("quantity", item.getQuantity());
                row.put("unitPrice", item.getUnitPrice());
                items.add(row);
            }
        }
        payload.put("items", items);
        return payload;
    }
}
