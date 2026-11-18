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

/**
 * Hỏi event-service xem người gọi có quyền gì trên một sự kiện và lưu tạm kết quả trong bộ nhớ.
 * Khóa cache gồm mã sự kiện và băm của header Authorization, nên mỗi token có một bản ghi riêng.
 */
@Slf4j
@Component
public class EventAccessClient {

    private static final int MAX_ENTRIES = 5000;

    /** Khóa cache: sự kiện và băm SHA-256 của header Authorization (không lưu token thô). */
    private record Key(String eventId, String tokenHash) {}
    /** Giá trị cache: kết quả quyền truy cập và thời điểm lưu (mili giây). */
    private record Entry(EventAccessView view, long storedAt) {}

    private final EventServiceClient events;
    private final EventScopeProperties scope;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /**
     * @param events Feign client gọi event-service
     * @param scope cấu hình thời gian cache và token nội bộ
     */
    public EventAccessClient(EventServiceClient events, EventScopeProperties scope) {
        this.events = events;
        this.scope = scope;
    }

    /**
     * Khi ứng dụng sẵn sàng, ghi log lỗi nếu token nội bộ để trống, vì khi đó các lời gọi tới payment-service bị từ chối.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: calls from booking-service to payment-service "
                    + "carry no X-Internal-Token and are refused until it is set to the shared secret");
        }
    }

    /**
     * Trả về quyền truy cập của người gọi, hoặc ném lỗi 401 / 403 / 404 / 503. {@code permission} là tên một Permission của event-service.
     *
     * @param eventId mã sự kiện
     * @param permission tên quyền cần có
     * @param authorization header Authorization của người gọi
     * @return quyền truy cập đã xác nhận
     * @throws ResponseStatusException khi thiếu mã sự kiện (404), chưa đăng nhập (401), không đủ quyền (403) hoặc không xác định được quyền (503)
     */
    public EventAccessView require(String eventId, String permission, String authorization) {
        if (eventId == null || eventId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        EventAccessView view = lookup(eventId, authorization, permission.startsWith("CHECKIN_"));
        if (!view.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return view;
    }

    /**
     * Lấy quyền truy cập từ cache hoặc từ event-service. Chỉ riêng quyền {@code CHECKIN_*} được dùng lại bản cache cũ
     * (trong {@code staleCheckinSeconds}) khi event-service không phản hồi, để việc soát vé không bị gián đoạn.
     *
     * @param eventId mã sự kiện
     * @param authorization header Authorization của người gọi
     * @param staleAllowed {@code true} nếu cho phép dùng bản cache quá hạn khi event-service lỗi
     * @return quyền truy cập của người gọi
     * @throws ResponseStatusException 401, 403, 404 theo phản hồi của event-service, hoặc 503 khi không xác định được quyền
     */
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
            // event-service từ chối người không phải thành viên ACTIVE; không lưu gì vào cache và câu trả lời vẫn như vậy
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

    /**
     * Dọn cache khi đầy: xóa các bản ghi đã quá hạn dùng lại, và xóa toàn bộ nếu vẫn còn đầy.
     *
     * @param now thời điểm hiện tại (mili giây)
     */
    private void evict(long now) {
        long keep = Math.max(scope.getCacheSeconds(), scope.getStaleCheckinSeconds()) * 1000;
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt() >= keep);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
    }

    /** Xóa toàn bộ cache quyền truy cập. */
    public void clear() {
        cache.clear();
    }

    /**
     * Băm SHA-256 một chuỗi thành chuỗi hex.
     *
     * @param value chuỗi cần băm
     * @return chuỗi hex của giá trị băm
     */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
