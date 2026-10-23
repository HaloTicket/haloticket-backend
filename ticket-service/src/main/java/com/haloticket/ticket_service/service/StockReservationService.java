package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.entity.TicketStockReservation;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.StockReservationStatus;
import com.haloticket.ticket_service.repository.TicketStockReservationRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * holds ticket_types stock for unpaid general-admission bookings. The final source of truth stays
 * ticket_types.total_quantity: a hold only raises reserved_quantity, and is consumed in the same transaction
 * that issues the tickets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockReservationService {

    private final TicketTypeRepository ticketTypes;
    private final TicketStockReservationRepository reservations;

    public record Item(String ticketTypeId, int quantity) {}

    /** raised when a requested type cannot cover its quantity; the whole reservation is rolled back. */
    public static class SoldOutException extends RuntimeException {
        private final String ticketTypeId;
        private final int available;

        public SoldOutException(String ticketTypeId, int available) {
            super("TICKET_SOLD_OUT");
            this.ticketTypeId = ticketTypeId;
            this.available = available;
        }

        public String getTicketTypeId() { return ticketTypeId; }

        public int getAvailable() { return available; }
    }

    /**
     * all-or-nothing, idempotent per booking. 
     * a booking that already has held rows gets the same answer again;
     * one whose rows were released or expired cannot be re-reserved under the same id.
     */
    @Transactional
    public void reserve(String bookingId, String eventId, String showtimeId, Instant expiresAt, List<Item> items) {
        if (bookingId == null || bookingId.isBlank() || eventId == null || eventId.isBlank()
                || items == null || items.isEmpty() || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST");
        }

        List<TicketStockReservation> existing = reservations.findByBookingIdForUpdate(bookingId);
        if (!existing.isEmpty()) {
            boolean allHeld = existing.stream().allMatch(r -> r.getStatus() == StockReservationStatus.HELD);
            if (allHeld) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_CLOSED");
        }

        // merge duplicates and lock in a stable order so concurrent bookings cannot deadlock.
        Map<String, Integer> wanted = new TreeMap<>();
        for (Item item : items) {
            if (item == null || item.ticketTypeId() == null || item.ticketTypeId().isBlank() || item.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST");
            }
            TicketType type = resolveType(item.ticketTypeId(), showtimeId, eventId);
            wanted.merge(type.getId(), item.quantity(), Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            int updated = ticketTypes.reserveAtomic(entry.getKey(), entry.getValue());
            if (updated == 0) {
                throw new SoldOutException(entry.getKey(), availableOf(entry.getKey()));
            }
            reservations.save(TicketStockReservation.builder()
                    .bookingId(bookingId)
                    .ticketTypeId(entry.getKey())
                    .quantity(entry.getValue())
                    .status(StockReservationStatus.HELD)
                    .expiresAt(expiresAt)
                    .build());
        }
        log.info("[GA_RESERVATION] reserved bookingId={} types={}", shortId(bookingId), wanted.size());
    }

    /** idempotent. returns how many held rows were released. */
    @Transactional
    public int release(String bookingId) {
        return close(reservations.findByBookingIdForUpdate(bookingId), StockReservationStatus.RELEASED);
    }

    /** called from ticket issuance: held stock turns into issued tickets inside the same transaction. */
    @Transactional
    public int consumeForBooking(String bookingId) {
        return close(reservations.findByBookingIdForUpdate(bookingId), StockReservationStatus.CONSUMED);
    }

    @Transactional
    public int expireDue(int limit) {
        int count = close(reservations.findDueForExpiry(Instant.now(), limit), StockReservationStatus.EXPIRED);
        if (count > 0) log.info("[GA_RESERVATION] expired {} stock hold(s)", count);
        return count;
    }

    /** same type resolution as ticket issuance, so the hold lands on the row that issuance will decrement. */
    public TicketType resolveType(String typeId, String showtimeId, String eventId) {
        TicketType requested = ticketTypes.findById(typeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_TYPE_NOT_FOUND"));
        if (!eventId.equals(requested.getEventId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TICKET_TYPE_EVENT_MISMATCH");
        }
        if (showtimeId == null || showtimeId.isBlank()) return requested;
        List<TicketType> showtimeTypes = ticketTypes.findByShowtimeId(showtimeId);
        if (showtimeTypes.isEmpty()) return requested;
        return showtimeTypes.stream().filter(t -> t.getId().equals(typeId)).findFirst()
                .or(() -> showtimeTypes.stream()
                        .filter(t -> t.getName() != null && requested.getName() != null
                                && t.getName().trim().equalsIgnoreCase(requested.getName().trim()))
                        .findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "TICKET_TYPE_UNAVAILABLE_FOR_SHOWTIME"));
    }

    private int close(List<TicketStockReservation> rows, StockReservationStatus target) {
        int count = 0;
        for (TicketStockReservation row : rows) {
            if (row.getStatus() != StockReservationStatus.HELD) continue;
            ticketTypes.releaseReservedAtomic(row.getTicketTypeId(), row.getQuantity());
            row.setStatus(target);
            reservations.save(row);
            count++;
        }
        return count;
    }

    private int availableOf(String typeId) {
        return ticketTypes.findById(typeId)
                .map(t -> Math.max(0, (t.getTotalQuantity() == null ? 0 : t.getTotalQuantity())
                        - (t.getReservedQuantity() == null ? 0 : t.getReservedQuantity())))
                .orElse(0);
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
