package com.haloticket.event_service.service;

import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.dto.request.EventCreateRequest.TicketCreateRequest;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pushes ticket types to ticket-service with a bounded retry. Never runs inside a database transaction (the callers are not
 * transactional). Retries only connection problems, timeouts and 5xx; any 4xx is the caller's problem and is never retried:
 * 422 becomes {@link TicketSyncRejectedException}, other 4xx become {@link TicketSyncFailedException}.
 */
@Component
public class TicketSyncService {
    private static final Logger log = LoggerFactory.getLogger(TicketSyncService.class);

    /** One call to ticket-service: showtimeId is null for an event without showtimes. */
    public record Batch(String showtimeId, List<TicketCreateRequest> tickets) {}

    private final TicketServiceClient ticketServiceClient;
    private final int maxAttempts;
    private final long backoffMs;

    public TicketSyncService(TicketServiceClient ticketServiceClient,
                             @Value("${event.ticket-sync.max-attempts:3}") int maxAttempts,
                             @Value("${event.ticket-sync.backoff-ms:300}") long backoffMs) {
        this.ticketServiceClient = ticketServiceClient;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    /** Sends the batches one by one and stops at the first one that fails. */
    public void syncAll(String eventId, List<Batch> batches) {
        for (Batch batch : batches) {
            syncBatch(eventId, batch.showtimeId(), batch.tickets());
        }
    }

    void syncBatch(String eventId, String showtimeId, List<TicketCreateRequest> tickets) {
        long wait = backoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ticketServiceClient.syncTicketTypesForEvent(eventId, showtimeId, tickets);
                return;
            } catch (TicketSyncRejectedException rejected) {
                log.warn("[TICKET_SYNC_REJECTED] eventId={} attempt={} code={}", shortId(eventId), attempt, rejected.getCode());
                throw rejected.forEvent(eventId);
            } catch (RuntimeException e) {
                String code = codeOf(e);
                log.error("[TICKET_SYNC_FAILED] eventId={} attempt={} code={}", shortId(eventId), attempt, code);
                if (isClientError(e) || attempt == maxAttempts) {
                    throw new TicketSyncFailedException(eventId, code);
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TicketSyncFailedException(eventId, code);
                }
                wait *= 3;
            }
        }
    }

    private static boolean isClientError(RuntimeException e) {
        return e instanceof FeignException fe && fe.status() >= 400 && fe.status() < 500;
    }

    /** A short code, never a message: the HTTP status or the exception class. */
    static String codeOf(RuntimeException e) {
        String code = e instanceof FeignException fe && fe.status() > 0 ? "HTTP_" + fe.status() : e.getClass().getSimpleName();
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    private static String shortId(String eventId) {
        if (eventId == null) return "null";
        return eventId.length() > 8 ? eventId.substring(0, 8) : eventId;
    }
}
