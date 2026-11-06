package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.EventCreateRequest.TicketCreateRequest;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.service.TicketSyncService.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds what is sent to ticket-service. Deterministic: the same event and request always give the same payloads, so saving
 * twice (or resyncing) sends the same thing. The number issued is carried as initialQuantity; when the request has only
 * totalQuantity that value is used (both fields are set to it, the legacy field kept for compatibility).
 */
public final class TicketPayloads {
    private TicketPayloads() {}

    /** The number issued: initialQuantity, else the legacy totalQuantity. */
    public static Integer issued(TicketCreateRequest t) {
        return t.getInitialQuantity() != null ? t.getInitialQuantity() : t.getTotalQuantity();
    }

    /** Full copy (id and showtimeId kept) with the quantities normalised. */
    public static TicketCreateRequest copy(TicketCreateRequest t) {
        TicketCreateRequest c = new TicketCreateRequest();
        c.setId(t.getId());
        c.setShowtimeId(t.getShowtimeId());
        fill(c, t);
        return c;
    }

    /** Copy for one showtime: the id is carried over only when the ticket already belongs to that showtime. */
    public static TicketCreateRequest cloneFor(TicketCreateRequest t, String showtimeId) {
        TicketCreateRequest c = new TicketCreateRequest();
        if (showtimeId != null && showtimeId.equals(t.getShowtimeId())) c.setId(t.getId());
        c.setShowtimeId(showtimeId);
        fill(c, t);
        return c;
    }

    private static void fill(TicketCreateRequest c, TicketCreateRequest t) {
        Integer issued = issued(t);
        c.setName(t.getName());
        c.setPrice(t.getPrice());
        c.setIsFree(t.getIsFree());
        c.setInitialQuantity(issued);
        c.setTotalQuantity(issued);
        c.setMinPerOrder(t.getMinPerOrder());
        c.setMaxPerOrder(t.getMaxPerOrder());
        c.setDescription(t.getDescription());
    }

    /**
     * An event with showtimes is synced per showtime only (never at event level): a showtime's own tickets when it has any,
     * else the event-level tickets cloned for it. An event without showtimes is synced once with showtimeId = null.
     */
    public static List<Batch> batches(List<EventShowtime> showtimes, List<TicketCreateRequest> eventLevel,
                                      Function<EventShowtime, List<TicketCreateRequest>> ownTickets) {
        List<Batch> batches = new ArrayList<>();
        boolean hasEventLevel = eventLevel != null && !eventLevel.isEmpty();
        if (showtimes == null || showtimes.isEmpty()) {
            if (hasEventLevel) {
                List<TicketCreateRequest> copies = new ArrayList<>();
                for (TicketCreateRequest t : eventLevel) copies.add(copy(t));
                batches.add(new Batch(null, copies));
            }
            return batches;
        }
        for (EventShowtime st : showtimes) {
            List<TicketCreateRequest> source = ownTickets.apply(st);
            if (source == null || source.isEmpty()) source = hasEventLevel ? eventLevel : null;
            if (source == null) continue;
            List<TicketCreateRequest> clones = new ArrayList<>();
            for (TicketCreateRequest t : source) clones.add(cloneFor(t, st.getId()));
            batches.add(new Batch(st.getId(), clones));
        }
        return batches;
    }
}
