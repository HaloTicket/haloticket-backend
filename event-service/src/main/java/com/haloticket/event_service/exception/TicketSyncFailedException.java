package com.haloticket.event_service.exception;

/** The ticket types could not be pushed to ticket-service (after the bounded retries). The event itself stays saved. */
public class TicketSyncFailedException extends RuntimeException {
    private final String eventId;
    private final String errorCode;

    public TicketSyncFailedException(String eventId, String errorCode) {
        super("TICKETS_SYNC_FAILED");
        this.eventId = eventId;
        this.errorCode = errorCode;
    }

    public String getEventId() { return eventId; }
    public String getErrorCode() { return errorCode; }
}
