package com.haloticket.event_service.exception;

import java.util.Map;

/**
 * ticket-service refused the ticket types with HTTP 422 (for example QUANTITY_BELOW_SOLD_AND_HELD): the organizer's input is
 * the problem, so this is never retried and is answered to the caller as 422 with the same code and details.
 */
public class TicketSyncRejectedException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final String eventId;

    public TicketSyncRejectedException(String code, Map<String, Object> details, String eventId) {
        super(code);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.eventId = eventId;
    }

    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
    public String getEventId() { return eventId; }

    public TicketSyncRejectedException forEvent(String id) {
        return new TicketSyncRejectedException(code, details, id);
    }
}
