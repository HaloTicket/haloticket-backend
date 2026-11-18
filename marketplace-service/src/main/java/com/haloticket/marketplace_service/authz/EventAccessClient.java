package com.haloticket.marketplace_service.authz;

import com.haloticket.marketplace_service.client.EventServiceClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hỏi event-service xem người gọi có quyền gì trên một sự kiện ({@code my-access}). Kết quả được cache trong bộ nhớ theo cặp (sự kiện, mã băm của header Authorization) trong {@code cacheSeconds} giây; riêng các quyền check-in được phép dùng kết quả cũ tối đa {@code staleCheckinSeconds} giây khi event-service ngừng hoạt động, để cổng soát vé không bị gián đoạn. */
@Slf4j
@Component
public class EventAccessClient {

    private static final int MAX_ENTRIES = 5000;

    /** Khóa cache: sự kiện và mã băm SHA-256 của header Authorization (không lưu token thô). */
    private record Key(String eventId, String tokenHash) {}
    /** Một mục cache: kết quả quyền và thời điểm lưu (mili giây). */
    private record Entry(EventAccessView view, long storedAt) {}

    private final EventServiceClient events;
    private final EventScopeProperties scope;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /** Tạo client với Feign client của event-service và cấu hình thời gian cache. */
    public EventAccessClient(EventServiceClient events, EventScopeProperties scope) {
        this.events = events;
        this.scope = scope;
    }

    /**
     * Bắt buộc người gọi có quyền {@code permission} trên sự kiện, bằng cách hỏi event-service (có cache ngắn hạn).
     *
     * @param eventId id sự kiện
     * @param permission tên một giá trị {@code Permission} của event-service
     * @param authorization header Authorization của người gọi
     * @return vai trò và tập quyền của người gọi
     * @throws ResponseStatusException 401 khi chưa đăng nhập, 403 khi thiếu quyền, 404 khi không có sự kiện, 503 khi không hỏi được event-service
     */
    public EventAccessView require(String eventId, String permission, String authorization) {
        if (eventId == null || eventId.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        EventAccessView view = lookup(eventId, authorization, permission.startsWith("CHECKIN_"));
        if (!view.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return view;
    }

    /** Lấy quyền từ cache nếu còn mới, ngược lại gọi event-service rồi lưu lại. Khi event-service lỗi, chỉ các quyền check-in ({@code staleAllowed}) mới được dùng kết quả cũ còn trong hạn. */
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
            // event-service từ chối người không phải thành viên ACTIVE; không lưu gì vào cache và câu trả lời luôn như nhau
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

    /** Dọn cache khi đầy: bỏ các mục đã quá hạn, nếu vẫn đầy thì xóa toàn bộ. */
    private void evict(long now) {
        long keep = Math.max(scope.getCacheSeconds(), scope.getStaleCheckinSeconds()) * 1000;
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt() >= keep);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
    }

    /** Xóa toàn bộ cache, dùng khi cần buộc hỏi lại event-service. */
    public void clear() {
        cache.clear();
    }

    /** Tính SHA-256 dạng hex của chuỗi, dùng làm khóa cache thay cho token thô. */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
