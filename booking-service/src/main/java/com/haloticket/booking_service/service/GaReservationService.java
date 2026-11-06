package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.entity.Booking;
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
 * part of an order is general admission and calls reserve/release.
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

    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenInternalTokenIsBlank() {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            log.error("[GA_RESERVATION] TICKET_RESERVATION_INTERNAL_TOKEN is blank: every general-admission reservation is refused with 503 until it is set");
        }
    }

    /**
     * Splits an order. A GA item is one that is flagged generalAdmission or any item of an order without seat ids;
     * the remaining seat ids go through the per-seat flow.
     */
    public Plan plan(BookingCreateRequest request) {
        List<String> seatIds = request.getSeatIds();
        List<BookingItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            return new Plan(List.of(), seatIds);
        }

        List<String> remainingSeats = new ArrayList<>(seatIds == null ? List.of() : seatIds);
        Map<String, Integer> ga = new LinkedHashMap<>();

        for (BookingItemRequest item : items) {
            boolean flagged = Boolean.TRUE.equals(item.getGeneralAdmission());
            boolean noSeats = remainingSeats.isEmpty();
            if (flagged || noSeats) {
                ga.merge(item.getTicketTypeId(), requirePositive(item.getQuantity()), Integer::sum);
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
            String code = codeOf(conflict);
            if ("TICKET_SOLD_OUT".equals(code)) {
                log.info("[GA_RESERVATION] sold out bookingId={}", shortId(bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SOLD_OUT");
            }
            if ("RESERVATION_CLOSED".equals(code)) {
                log.info("[GA_RESERVATION] reservation already closed bookingId={}", shortId(bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_CLOSED");
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

    /** Gives back a hold made for this booking. */
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

    /**
     * Takes back the hold of a booking that was cancelled or expired before its payment arrived. Refusals that
     * change nothing (sold out, not reopenable) are an answer; anything else (unreachable, token) throws so the
     * caller can retry.
     */
    public ReopenOutcome reopen(String bookingId, Instant expiresAt) {
        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: internal token is not configured");
        }
        try {
            ticketStockClient.reopen(token, bookingId, Map.of("expiresAt", expiresAt.toString()));
        } catch (FeignException.Conflict conflict) {
            String code = codeOf(conflict);
            if ("TICKET_SOLD_OUT".equals(code) || "RESERVATION_NOT_REOPENABLE".equals(code)) {
                log.info("[GA_RESERVATION] reopen refused bookingId={} code={}", shortId(bookingId), code);
                return new ReopenOutcome(false, code);
            }
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: reopen refused with code " + code, conflict);
        } catch (RuntimeException e) {
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: reopen failed", e);
        }
        // the booking goes back to PAID in the caller's transaction; if that does not commit, give the stock back
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
        log.info("[GA_RESERVATION] reopened bookingId={}", shortId(bookingId));
        return new ReopenOutcome(true, null);
    }

    /** reopened=false means ticket-service refused with {@code code} and changed nothing. */
    public record ReopenOutcome(boolean reopened, String code) {}

    private String codeOf(FeignException exception) {
        try {
            JsonNode node = objectMapper.readTree(exception.contentUTF8());
            return node.path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static int requirePositive(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Số lượng vé không hợp lệ.");
        }
        return quantity;
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
