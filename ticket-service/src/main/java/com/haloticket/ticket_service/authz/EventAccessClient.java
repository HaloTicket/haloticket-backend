package com.haloticket.ticket_service.authz;

import com.haloticket.ticket_service.client.EventServiceClient;
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
 * Kiểm tra quyền của người gọi trên một sự kiện bằng cách hỏi event-service ({@code my-access}) và lưu tạm kết quả
 * trong bộ nhớ theo cặp (sự kiện, hash token). Với quyền check-in, khi event-service tạm lỗi vẫn dùng lại kết quả cũ
 * trong thời gian cho phép để cửa soát vé không bị gián đoạn.
 */
@Slf4j
@Component
public class EventAccessClient {

    private static final int MAX_ENTRIES = 5000;

    /** Khóa của bộ nhớ tạm: sự kiện và hash SHA-256 của token người gọi. */
    private record Key(String eventId, String tokenHash) {}
    /** Một kết quả quyền được lưu tạm cùng thời điểm lưu (mili giây). */
    private record Entry(EventAccessView view, long storedAt) {}

    private final EventServiceClient events;
    private final EventScopeProperties scope;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /** Khởi tạo với Feign client gọi event-service và cấu hình phạm vi sự kiện. */
    public EventAccessClient(EventServiceClient events, EventScopeProperties scope) {
        this.events = events;
        this.scope = scope;
    }

    /** Khi ứng dụng sẵn sàng, ghi log lỗi nếu chưa cấu hình token nội bộ vì mọi lời gọi giữa các dịch vụ sẽ bị từ chối. */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: every service-to-service call to this service "
                    + "(event-service ticket sync, full ticket-type listing) is refused until it is set to the shared secret");
        }
    }

    /**
     * Trả về quyền của người gọi trên sự kiện, hoặc ném lỗi 401 / 403 / 404 / 503. {@code permission} là tên một Permission của event-service.
     * Quyền có tiền tố {@code CHECKIN_} được phép dùng kết quả cũ khi event-service tạm lỗi.
     *
     * @param eventId mã sự kiện
     * @param permission tên quyền cần có
     * @param authorization header {@code Authorization} của người gọi
     * @return thông tin quyền của người gọi
     * @throws ResponseStatusException 404 khi thiếu mã sự kiện hoặc sự kiện không tồn tại, 401 khi chưa đăng nhập, 403 khi không đủ quyền, 503 khi event-service không trả lời
     */
    public EventAccessView require(String eventId, String permission, String authorization) {
        if (eventId == null || eventId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        EventAccessView view = lookup(eventId, authorization, permission.startsWith("CHECKIN_"));
        if (!view.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return view;
    }

    /**
     * Lấy quyền từ bộ nhớ tạm nếu còn hạn, nếu không thì gọi event-service và lưu lại. Khi event-service lỗi và
     * {@code staleAllowed} bằng {@code true}, dùng lại kết quả cũ chưa quá thời gian cho phép.
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
            // event-service từ chối người không phải thành viên ACTIVE; không lưu gì và câu trả lời luôn như vậy
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

    /** Dọn các mục quá hạn khỏi bộ nhớ tạm; nếu vẫn còn đầy thì xóa toàn bộ. */
    private void evict(long now) {
        long keep = Math.max(scope.getCacheSeconds(), scope.getStaleCheckinSeconds()) * 1000;
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt() >= keep);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
    }

    /** Xóa toàn bộ bộ nhớ tạm quyền. */
    public void clear() {
        cache.clear();
    }

    /** Cho biết header mang đúng secret dịch vụ đã cấu hình hay không; secret rỗng thì không bao giờ khớp. So sánh theo thời gian không đổi. */
    public boolean isInternal(String token) {
        String configured = scope.getInternalToken();
        return !configured.isBlank() && token != null && MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /** Băm SHA-256 token ra chuỗi hex để dùng làm khóa bộ nhớ tạm mà không giữ token gốc. */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
