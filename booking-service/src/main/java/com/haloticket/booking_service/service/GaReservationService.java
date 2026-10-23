package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.util.GeneralAdmissionSeatIds;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock holds for general-admission (no seatmap) orders. ticket-service owns the numbers; this class decides which
 * part of an order is general admission and calls reserve/release. Everything is a no-op while
 * booking.ga.reservation.enabled is false.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GaReservationService {

    public record GaItem(String ticketTypeId, int quantity) {}

    // what is held as stock (gaItems) and what still goes through the per-seat flow (seatIds)
    public record Plan(List<GaItem> gaItems, List<String> seatIds) {
        public boolean hasGa() {
            return gaItems != null && !gaItems.isEmpty();
        }

        public boolean hasSeats() {
            return seatIds != null && !seatIds.isEmpty();
        }
    }

    private final GaReservationProperties properties;
    private final TicketStockClient ticketStockClient;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    // Marks the switch-over so behaviour data before and after it can be told apart
    @EventListener(ApplicationReadyEvent.class)
    public void logStartup() {
        log.info("[GA_RESERVATION] enabled={} startedAt={}", properties.isEnabled(), Instant.now());
        if (properties.isEnabled() && (properties.getInternalToken() == null || properties.getInternalToken().isBlank())) {
            log.error("[GA_RESERVATION] enabled but TICKET_RESERVATION_INTERNAL_TOKEN is blank: every GA reservation will fail");
        }
    }

    /**
     * Splits an order. With the flag off the request is returned untouched. With it on, a GA item is one that is
     * flagged generalAdmission, any item of an order without seat ids, or one that an older client represents with
     * "<ticketTypeId>-STT-<n>" virtual seats (those ids are dropped so no virtual seat is held).
     */
    public Plan plan(BookingCreateRequest request) {
        List<String> seatIds = request.getSeatIds();
        if (!properties.isEnabled()) {
            return new Plan(List.of(), seatIds);
        }
        List<BookingItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            return new Plan(List.of(), seatIds);
        }

        List<String> remainingSeats = new ArrayList<>(seatIds == null ? List.of() : seatIds);
        Map<String, Integer> ga = new LinkedHashMap<>();

        for (BookingItemRequest item : items) {
            boolean flagged = Boolean.TRUE.equals(item.getGeneralAdmission());
            boolean noSeats = remainingSeats.isEmpty();
            int virtualSeats = countVirtualSeats(remainingSeats, item.getTicketTypeId());
            if (flagged || noSeats) {
                remainingSeats.removeIf(id -> belongsTo(id, item.getTicketTypeId()));
                ga.merge(item.getTicketTypeId(), requirePositive(item.getQuantity()), Integer::sum);
            } else if (virtualSeats > 0) {
                remainingSeats.removeIf(id -> belongsTo(id, item.getTicketTypeId()));
                ga.merge(item.getTicketTypeId(), virtualSeats, Integer::sum);
            }
        }
        List<GaItem> gaItems = ga.entrySet().stream().map(e -> new GaItem(e.getKey(), e.getValue())).toList();
        return new Plan(gaItems, remainingSeats);
    }

    /**
     * Holds the stock; any failure rolls the booking back. Sold out maps to 409 TICKET_SOLD_OUT, anything else
     * (ticket-service down, token wrong) to 503 so nothing is sold without a hold.
     */
    public void reserve(String bookingId, BookingCreateRequest request, Plan plan, LocalDateTime bookingExpiresAt) {
        Instant holdUntil = bookingExpiresAt.atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(Math.max(0, properties.getSlackSeconds()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("eventId", request.getEventId());
        body.put("showtimeId", request.getShowtimeId());
        body.put("expiresAt", holdUntil.toString());
        body.put("items", plan.gaItems().stream()
                .map(i -> Map.<String, Object>of("ticketTypeId", i.ticketTypeId(), "quantity", i.quantity()))
                .toList());

        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            log.error("[GA_RESERVATION] cannot reserve bookingId={}: internal token is not configured", shortId(bookingId));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_STOCK_UNAVAILABLE");
        }
        try {
            ticketStockClient.reserve(token, body);
        } catch (FeignException.Conflict conflict) {
            if (isSoldOut(conflict)) {
                log.info("[GA_RESERVATION] sold out bookingId={}", shortId(bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SOLD_OUT");
            }
            log.warn("[GA_RESERVATION] reserve refused bookingId={}: {}", shortId(bookingId), conflict.status());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_RESERVATION_REFUSED");
        } catch (RuntimeException e) {
            log.error("[GA_RESERVATION] reserve failed bookingId={}: {}", shortId(bookingId), e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_STOCK_UNAVAILABLE");
        }

        // If this booking's transaction does not commit, give the stock back right away; the hold's own expiry
        // in ticket-service is the fallback when this call fails too.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        releaseQuietly(bookingId);
                    }
                }
            });
        }
    }

    /** Gives back a hold made for this booking. Regardless of the flag, so a booking made earlier still releases. */
    public void releaseIfReserved(Booking booking) {
        if (booking != null && Boolean.TRUE.equals(booking.getGaReserved())) {
            releaseQuietly(booking.getId());
        }
    }

    public void releaseQuietly(String bookingId) {
        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            log.error("[GA_RESERVATION] cannot release bookingId={}: internal token is not configured; the hold will expire on its own",
                    shortId(bookingId));
            return;
        }
        try {
            ticketStockClient.release(token, bookingId);
            log.info("[GA_RESERVATION] released bookingId={}", shortId(bookingId));
        } catch (RuntimeException e) {
            log.warn("[GA_RESERVATION] release failed bookingId={} (hold will expire on its own): {}", shortId(bookingId), e.getMessage());
        }
    }

    private boolean isSoldOut(FeignException exception) {
        try {
            JsonNode node = objectMapper.readTree(exception.contentUTF8());
            return "TICKET_SOLD_OUT".equals(node.path("code").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static int requirePositive(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Số lượng vé không hợp lệ.");
        }
        return quantity;
    }

    private static boolean belongsTo(String seatId, String ticketTypeId) {
        return GeneralAdmissionSeatIds.isGeneralAdmission(seatId)
                && (ticketTypeId != null && ticketTypeId.equals(GeneralAdmissionSeatIds.extractTicketTypeId(seatId)));
    }

    private static int countVirtualSeats(List<String> seatIds, String ticketTypeId) {
        return (int) seatIds.stream().filter(id -> belongsTo(id, ticketTypeId)).count();
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
