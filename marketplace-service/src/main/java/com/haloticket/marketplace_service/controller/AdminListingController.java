package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Bộ đếm chỉ đọc cho bảng điều khiển admin. Gateway đã giới hạn {@code /api/marketplace/admin/**} cho {@code ROLE_ADMIN};
 * vai trò vẫn được kiểm tra lại ở đây vì service này không có ánh xạ vai trò riêng và không được tin vào đường đi mạng.
 */
@RestController
@RequestMapping("/api/marketplace/admin")
public class AdminListingController {
    /** Số tin bán đang chờ duyệt và thời điểm tạo của tin chờ lâu nhất. */
    public record PendingSummary(long pendingCount, Instant oldestPendingAt) {}

    private final TicketListingRepository listings;

    /**
     * Tạo controller.
     *
     * @param listings repository tin bán vé
     */
    public AdminListingController(TicketListingRepository listings) {
        this.listings = listings;
    }

    /**
     * Trả số tin đang ở trạng thái {@code PENDING_APPROVAL} và thời điểm tạo của tin cũ nhất, cho bảng điều khiển admin.
     *
     * @param authentication thông tin xác thực của người gọi
     * @return tóm tắt tin chờ duyệt
     */
    @GetMapping("/listings/summary")
    public PendingSummary pendingSummary(Authentication authentication) {
        requireAdmin(authentication);
        return new PendingSummary(listings.countByStatus(ListingStatus.PENDING_APPROVAL),
                listings.oldestCreatedAtByStatus(ListingStatus.PENDING_APPROVAL));
    }

    /**
     * Đảm bảo người gọi là admin; ném HTTP 403 {@code ADMIN_ONLY} nếu không phải JWT hợp lệ hoặc không có vai trò admin.
     * Vai trò được tìm ở claim {@code roles} và {@code realm_access.roles}.
     *
     * @param authentication thông tin xác thực của người gọi
     */
    static void requireAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_ONLY");
        }
        Object direct = token.getToken().getClaim("roles");
        Object realm = token.getToken().getClaim("realm_access");
        boolean admin = hasAdmin(direct) || (realm instanceof Map<?, ?> map && hasAdmin(map.get("roles")));
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_ONLY");
        }
    }

    /**
     * Kiểm tra tập vai trò có chứa {@code ADMIN} hoặc {@code ROLE_ADMIN} (không phân biệt hoa thường) hay không.
     *
     * @param roles giá trị claim vai trò, thường là một collection
     * @return {@code true} nếu có vai trò admin
     */
    private static boolean hasAdmin(Object roles) {
        return roles instanceof Collection<?> values && values.stream()
                .anyMatch(v -> "ADMIN".equalsIgnoreCase(String.valueOf(v)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(v)));
    }
}
