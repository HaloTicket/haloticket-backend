package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.service.StockReservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service only. The gateway can still route to it, so every call must carry the shared token; an
 * unset token rejects everything.
 */
@RestController
@RequestMapping("/api/internal/tickets/stock-reservations")
public class InternalStockReservationController {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final StockReservationService stockReservations;
    private final String serviceToken;

    public InternalStockReservationController(StockReservationService stockReservations,
            @Value("${ticket.stock-reservation.internal-token:}") String serviceToken) {
        this.stockReservations = stockReservations;
        this.serviceToken = serviceToken;
    }

    public record ReserveRequest(String bookingId, String eventId, String showtimeId, Instant expiresAt, List<ReserveItem> items) {}

    public record ReserveItem(String ticketTypeId, Integer quantity) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody ReserveRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        List<StockReservationService.Item> items = request.items() == null ? List.of()
                : request.items().stream()
                        .map(i -> new StockReservationService.Item(i == null ? null : i.ticketTypeId(),
                                i == null || i.quantity() == null ? 0 : i.quantity()))
                        .toList();
        try {
            stockReservations.reserve(request.bookingId(), request.eventId(), request.showtimeId(), request.expiresAt(), items);
        } catch (StockReservationService.SoldOutException soldOut) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "TICKET_SOLD_OUT");
            body.put("ticketTypeId", soldOut.getTicketTypeId());
            body.put("available", soldOut.getAvailable());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return ResponseEntity.ok(Map.of("reserved", true));
    }

    @PostMapping("/{bookingId}/release")
    public Map<String, Object> release(@PathVariable String bookingId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        return Map.of("released", stockReservations.release(bookingId));
    }

    private void requireToken(String token) {
        if (serviceToken.isBlank() || token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
