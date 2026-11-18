package com.haloticket.notification_service.authz;

import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.repository.NotificationRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Kiểm tra quyền truy cập API thông báo: chủ hộp thư, chủ thông báo, hoặc dịch vụ nội bộ có token chung. Admin luôn được phép.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScopeGuard {

    private final NotificationScopeProperties scope;
    private final UserServiceClient users;
    private final NotificationRepository notifications;

    /**
     * Ghi log lỗi khi ứng dụng khởi động xong mà token nội bộ vẫn trống, vì lúc đó các lời gọi giữa dịch vụ sẽ bị từ chối.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: service-to-service calls to this service "
                    + "(creating and publishing notifications) are refused until it is set to the shared secret");
        }
    }

    /**
     * Yêu cầu người gọi là chủ hộp thư của {@code userId}. Hợp lệ nếu là admin, nếu {@code subject} của JWT trùng, hoặc nếu mã người dùng
     * trong {@code user-service} (lấy theo token) trùng.
     *
     * @param userId mã người dùng của hộp thư
     * @param authorization header {@code Authorization} của người gọi
     * @throws ResponseStatusException HTTP 401 khi chưa xác thực, HTTP 403 {@code FORBIDDEN_OWNER} khi không phải chủ,
     *         HTTP 503 khi {@code user-service} không khả dụng
     */
    public void requireOwnerOfUserInbox(String userId, String authorization) {
        if (isAdmin()) return;
        String subject = subject();
        if (subject == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        if (subject.equals(userId)) return;
        // không phải subject: vẫn có thể là mã người dùng trong user-service của người đó (thông báo do event-service tạo dùng mã này)
        String own;
        try {
            var eligibility = users.getOrganizerEligibility(authorization);
            own = eligibility == null ? null : eligibility.userId();
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
        }
        if (own == null || !own.equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_OWNER");
    }

    /**
     * Yêu cầu người gọi là chủ của thông báo. Thông báo không tồn tại thì bỏ qua để controller tự trả lỗi không tìm thấy.
     *
     * @param notificationId mã thông báo
     * @param authorization header {@code Authorization} của người gọi
     */
    public void requireOwnerOfNotification(String notificationId, String authorization) {
        if (isAdmin()) return;
        var found = notifications.findById(notificationId);
        if (found.isEmpty()) return; // controller vẫn trả lỗi "không tìm thấy" như trước
        requireOwnerOfUserInbox(found.get().getUserId(), authorization);
    }

    /**
     * Cho phép admin hoặc lời gọi nội bộ mang token đúng; so sánh theo thời gian không đổi để tránh lộ thông tin.
     *
     * @param internalToken giá trị header {@code X-Internal-Token}
     * @throws ResponseStatusException HTTP 403 {@code FORBIDDEN} khi không phải admin và token trống, thiếu hoặc sai
     */
    public void requireInternalOrAdmin(String internalToken) {
        if (isAdmin()) return;
        String configured = scope.getInternalToken();
        if (configured.isBlank() || internalToken == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    /**
     * Kiểm tra người gọi hiện tại có vai trò {@code ROLE_ADMIN} hay không.
     *
     * @return {@code true} nếu là admin
     */
    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Lấy {@code subject} của JWT hiện tại.
     *
     * @return subject, hoặc {@code null} nếu chưa xác thực bằng JWT
     */
    private static String subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }
}
