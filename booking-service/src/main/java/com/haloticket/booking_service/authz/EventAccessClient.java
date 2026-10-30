package com.haloticket.booking_service.authz;

import com.haloticket.booking_service.client.EventServiceClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class EventAccessClient {

    private static final int MAX_ENTRIES = 5000;

    private record Key(String eventId, String tokenHash) {}
    private record Entry(EventAccessView view, long storedAt) {}

    private final EventServiceClient events;
    private final EventScopeProperties scope;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public EventAccessClient(EventServiceClient events, EventScopeProperties scope) {
        this.events = events;
        this.scope = scope;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: calls from booking-service to payment-service "
                    + "carry no X-Internal-Token and are refused until it is set to the shared secret");
        }
    }

    /** Returns the caller's access, or throws 401 / 403 / 404 / 503. {@code permission} is a name of event-service's Permission. */
    public EventAccessView require(String eventId, String permission, String authorization) {
        if (eventId == null || eventId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        EventAccessView view = lookup(eventId, authorization, permission.startsWith("CHECKIN_"));
        if (!view.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return view;
    }

    private EventAccessView lookup(String eventId, String authorization, boolean staleAllowed) {
        Key key = new Key(eventId, hash(authorization));
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && now - cached.storedAt() < scope.getCacheSeconds() * 1000) return cached.view();
        try {
            EventAccessView fresh = events.getMyAccess(eventId, authorization);
            if (fresh == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_ACCESS_UNAVAILABLE");
            if (cache.size() >= MAX_ENTRIES) evict(now);
            cache.put(key, new Entry(fresh, now));
            return fresh;
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (FeignException.Forbidden e) {
            // event-service refuses people who are not an ACTIVE member; nothing is remembered and the answer is the same
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (staleAllowed && cached != null && now - cached.storedAt() < scope.getStaleCheckinSeconds() * 1000) {
                log.warn("[EVENT_ACCESS_STALE] event-service unavailable, reusing an answer {}s old for event {}",
                        (now - cached.storedAt()) / 1000, eventId);
                return cached.view();
            }
            log.warn("[EVENT_ACCESS_UNAVAILABLE] event={} cause={}", eventId, e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_ACCESS_UNAVAILABLE");
        }
    }

    private void evict(long now) {
        long keep = Math.max(scope.getCacheSeconds(), scope.getStaleCheckinSeconds()) * 1000;
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt() >= keep);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
    }

    public void clear() {
        cache.clear();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
