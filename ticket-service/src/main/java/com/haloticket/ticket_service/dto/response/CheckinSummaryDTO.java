package com.haloticket.ticket_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * How many tickets of an event can enter and how many already did. "total" counts tickets that are still the holder's
 * (VALID, USED, or locked for a resale or a transfer); revoked and transferred tickets are not counted.
 */
public record CheckinSummaryDTO(String eventId, long total, long checkedIn, List<Group> byShowtime, List<Group> byTicketType,
                                LocalDateTime generatedAt) {

    /** One slice of the count: a showtime or a ticket type (id and name are null for the other kind). */
    public record Group(String id, String name, long total, long checkedIn) {}
}
