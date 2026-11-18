package com.haloticket.event_service.service;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.enums.StaffRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phân quyền theo từng sự kiện cho chính event-service. Mọi kiểm tra đều đi qua {@link EventPermissions}, nơi duy nhất đọc
 * bảng staff_assignments (chỉ các dòng ACTIVE); id người gọi trong user-service lấy từ {@link CallerIds}.
 */
@Service
@RequiredArgsConstructor
public class EventAccessService {
    private final EventPermissions permissions;
    private final CallerIds callers;

    /**
     * Lấy id người gọi trong user-service từ header Authorization.
     * @param authorization header Authorization của request
     * @return id người dùng
     */
    public String actor(String authorization) {
        return callers.userId(authorization);
    }

    /**
     * Tính quyền của người gọi đối với sự kiện. Không ném lỗi khi thiếu quyền (chỉ trả về tập quyền rỗng); sự kiện không tồn
     * tại thì trả 404.
     *
     * @param eventId id sự kiện
     * @param authorization header Authorization của request
     * @return vai trò và các quyền của người gọi trên sự kiện
     */
    public EventAccess forCaller(String eventId, String authorization) {
        String userId = callers.userId(authorization);
        return permissions.forUser(userId, eventId, isPlatformAdmin());
    }

    /**
     * Yêu cầu quyền đọc chính sách: chủ sự kiện, admin hoặc manager của sự kiện; admin nền tảng cũng được.
     *
     * @param eventId id sự kiện
     * @param authorization header Authorization của request
     * @return vai trò của người gọi trong sự kiện
     * @throws ResponseStatusException 403 {@code FORBIDDEN_ROLE} khi không đủ quyền
     */
    public StaffRole requireRead(String eventId, String authorization) {
        EventAccess access = forCaller(eventId, authorization);
        if (access.has(Permission.POLICY_READ)) return access.role();
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /**
     * Yêu cầu quyền ghi chính sách: chủ sự kiện, admin của sự kiện hoặc admin nền tảng.
     *
     * @param eventId id sự kiện
     * @param authorization header Authorization của request
     * @return id người gọi trong user-service, dùng làm người thực hiện khi ghi nhật ký
     * @throws ResponseStatusException 403 {@code FORBIDDEN_ROLE} khi không đủ quyền
     */
    public String requireWrite(String eventId, String authorization) {
        String userId = callers.userId(authorization);
        EventAccess access = permissions.forUser(userId, eventId, isPlatformAdmin());
        if (access.has(Permission.POLICY_WRITE)) return userId;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /**
     * Bắt buộc người gọi có quyền {@code permission} trên sự kiện: 401 khi chưa đăng nhập, 403 khi thiếu quyền, 404 khi sự kiện
     * không tồn tại.
     *
     * @param eventId id sự kiện
     * @param permission quyền cần có
     * @param authorization header Authorization của request
     * @return quyền của người gọi trên sự kiện
     */
    public EventAccess require(String eventId, Permission permission, String authorization) {
        EventAccess access = permissions.forUser(callers.userId(authorization), eventId, isPlatformAdmin());
        if (!access.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return access;
    }

    /** Giống {@code require}, nhưng tự lấy header Authorization từ request hiện tại, dùng cho controller không nhận header làm tham số. */
    public EventAccess requireForRequest(String eventId, Permission permission) {
        return require(eventId, permission, currentAuthorization());
    }

    /**
     * Bắt buộc người gọi là {@code ROLE_ADMIN} của nền tảng.
     *
     * @throws ResponseStatusException 401 khi chưa đăng nhập, 403 khi không phải admin
     */
    public void requirePlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (!isPlatformAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /** Kiểm tra người đang đăng nhập có quyền {@code ROLE_ADMIN} của nền tảng hay không (chưa đăng nhập thì trả false). */
    public static boolean isPlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** Đọc header Authorization của request đang xử lý; trả null khi không ở trong ngữ cảnh request. */
    private static String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
