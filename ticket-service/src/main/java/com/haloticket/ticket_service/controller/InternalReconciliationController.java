package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only reconciliation for booking-service. Service-to-service only: the gateway can still route here, so every call
 * must carry the shared token; an unset token rejects everything.
 */
@RestController
@RequestMapping("/api/internal/tickets")
public class InternalReconciliationController {

    static final String TOKEN_HEADER = "X-Internal-Token";
    static final int MAX_BOOKING_IDS = 500;
    static final int MAX_LIMIT = 500;
    static final int DEFAULT_LIMIT = 200;

    private final TicketRepository tickets;
    private final TicketTypeRepository ticketTypes;
    private final String serviceToken;

    public InternalReconciliationController(TicketRepository tickets, TicketTypeRepository ticketTypes,
            @Value("${ticket.stock-reservation.internal-token:}") String serviceToken) {
        this.tickets = tickets;
        this.ticketTypes = ticketTypes;
        this.serviceToken = serviceToken;
    }

    public record IssuedCountsRequest(List<String> bookingIds) {}

    /** Every requested id appears in the answer; a booking without tickets counts 0. */
    @PostMapping("/issued-counts")
    @Transactional(readOnly = true)
    public Map<String, Object> issuedCounts(@RequestBody IssuedCountsRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        List<String> raw = request == null || request.bookingIds() == null ? List.of() : request.bookingIds();
        if (raw.size() > MAX_BOOKING_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOO_MANY_BOOKING_IDS");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) ids.add(id);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String id : ids) counts.put(id, 0L);
        if (!ids.isEmpty()) {
            for (Object[] row : tickets.countIssuedByBookingIds(ids)) {
                counts.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return Map.of("counts", counts);
    }

    /** Only ticket types with a discrepancy or a note, worst-first is not needed: ordered by event and name. */
    @GetMapping("/stock-reconciliation")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockReconciliation(@RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        int effective = limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : ticketTypes.findStockDiscrepancies(effective)) {
            Integer initial = intOrNull(r[4]);
            Integer remaining = intOrNull(r[5]);
            int held = intOrNull(r[6]) == null ? 0 : intOrNull(r[6]);
            long issued = r[7] == null ? 0 : ((Number) r[7]).longValue();
            long heldByReservations = r[8] == null ? 0 : ((Number) r[8]).longValue();

            List<String> notes = new ArrayList<>();
            if (initial == null) notes.add("INITIAL_QUANTITY_NULL");
            if (remaining != null && remaining < 0) notes.add("REMAINING_NEGATIVE");
            if (remaining != null && held > remaining) notes.add("HELD_EXCEEDS_REMAINING");
            if (initial != null && remaining != null && remaining > initial) notes.add("REMAINING_EXCEEDS_INITIAL");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketTypeId", r[0]);
            row.put("eventId", r[1]);
            row.put("showtimeId", r[2]);
            row.put("name", r[3]);
            row.put("initial", initial);
            row.put("remaining", remaining);
            row.put("held", held);
            row.put("issued", issued);
            row.put("heldByReservations", heldByReservations);
            row.put("diffStockVsIssued", initial == null || remaining == null ? null : Math.max(0, initial - remaining) - issued);
            row.put("diffHeldVsReservations", held - heldByReservations);
            row.put("note", notes.isEmpty() ? null : String.join("; ", notes));
            result.add(row);
        }
        return result;
    }

    /**
     * Repairs one ticket type: remaining := initial - issued (a row without an initial number gets initial := remaining + issued).
     * Refused, without changing anything, when that would be negative or below what unpaid bookings hold.
     */
    @PostMapping("/stock-reconciliation/{ticketTypeId}/fix")
    @Transactional
    public Map<String, Object> fixStock(@PathVariable String ticketTypeId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        var type = ticketTypes.findById(ticketTypeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_TYPE_NOT_FOUND"));
        long issued = tickets.countPrimaryIssuedByTicketType(ticketTypeId);
        if (type.getInitialQuantity() == null) {
            if (type.getTotalQuantity() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INITIAL_QUANTITY_UNKNOWN");
            ticketTypes.backfillInitialQuantityAtomic(ticketTypeId);
        } else {
            long target = type.getInitialQuantity() - issued;
            if (target < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "INITIAL_BELOW_ISSUED");
            if (ticketTypes.setTotalQuantityAtomic(ticketTypeId, (int) target) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REMAINING_BELOW_HELD");
            }
        }
        var fresh = ticketTypes.findById(ticketTypeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_TYPE_NOT_FOUND"));
        Integer initial = fresh.getInitialQuantity();
        Integer remaining = fresh.getTotalQuantity();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketTypeId", fresh.getId());
        row.put("eventId", fresh.getEventId());
        row.put("showtimeId", fresh.getShowtimeId());
        row.put("name", fresh.getName());
        row.put("initial", initial);
        row.put("remaining", remaining);
        row.put("held", fresh.getReservedQuantity() == null ? 0 : fresh.getReservedQuantity());
        row.put("issued", issued);
        row.put("heldByReservations", null);
        row.put("diffStockVsIssued", initial == null || remaining == null ? null : Math.max(0, initial - remaining) - issued);
        row.put("diffHeldVsReservations", null);
        row.put("note", null);
        return row;
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private void requireToken(String token) {
        if (serviceToken.isBlank() || token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
