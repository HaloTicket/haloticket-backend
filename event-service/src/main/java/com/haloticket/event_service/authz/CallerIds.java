package com.haloticket.event_service.authz;

import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;

/** Đổi header Authorization của người gọi thành id người dùng trong user-service (không phải subject của Keycloak), có cache 60 giây theo subject. */
@Component
@RequiredArgsConstructor
public class CallerIds {

    private static final long TTL_MILLIS = 60_000;
    private static final int MAX_ENTRIES = 10_000;

    /** Một mục cache: id người dùng và thời điểm hết hạn (mili giây). */
    private record Cached(String userId, long expiresAt) {}

    private final UserServiceClient users;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Lấy id người dùng của người gọi: dùng cache nếu còn hạn, nếu không hỏi user-service bằng token của người gọi.
     * @param authorization giá trị header Authorization
     * @return id người dùng trong user-service
     * @throws ResponseStatusException HTTP 401 khi thiếu token hoặc bị từ chối, HTTP 503 khi user-service không trả lời
     */
    public String userId(String authorization) {
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        String subject = subject();
        long now = System.currentTimeMillis();
        if (subject != null) {
            Cached hit = cache.get(subject);
            if (hit != null && hit.expiresAt() > now) return hit.userId();
        }
        String userId;
        try {
            OrganizerEligibilityResponse response = users.getOrganizerEligibility(authorization);
            userId = response == null ? null : response.userId();
        } catch (FeignException.Unauthorized | FeignException.Forbidden rejected) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE", failure);
        }
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        if (subject != null) {
            if (cache.size() >= MAX_ENTRIES) cache.clear();
            cache.put(subject, new Cached(userId, now + TTL_MILLIS));
        }
        return userId;
    }

    /** Xóa toàn bộ cache id người dùng. */
    public void clear() {
        cache.clear();
    }

    /** Lấy tên chủ thể đã xác thực trong ngữ cảnh bảo mật; null nếu chưa đăng nhập hoặc là người dùng ẩn danh. */
    private static String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())
                ? authentication.getName() : null;
    }
}
