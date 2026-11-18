package com.haloticket.payment_service.authz;

import com.haloticket.payment_service.client.BookingServiceClient;
import com.haloticket.payment_service.client.UserServiceClient;
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
 * Các kiểm tra danh tính của cơ chế phân quyền theo sự kiện (docs/event-authz-plan.md) cho payment-service.
 * Luôn được chạy; token nội bộ để trống sẽ khiến mọi lời gọi giữa các dịch vụ bị từ chối.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScopeGuard {

    private final PaymentScopeProperties scope;
    private final UserServiceClient users;
    private final BookingServiceClient bookings;

    /** Khi ứng dụng sẵn sàng, ghi log lỗi nếu token nội bộ để trống (khi đó mọi lời gọi giữa các dịch vụ đều bị từ chối). */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: service-to-service calls to this service "
                    + "(bulk payment-method lookup from booking-service) are refused until it is set to the shared secret");
        }
    }

    /**
     * Tài khoản nhận tiền (thông tin ngân hàng) chỉ chủ tài khoản hoặc admin nền tảng được đọc. Chủ tài khoản là người
     * có mã user-service trùng mã trên đường dẫn; {@code me} luôn được chấp nhận.
     *
     * @param pathUserId mã người dùng trên đường dẫn, hoặc {@code me}
     * @param authorization header Authorization của yêu cầu
     * @throws ResponseStatusException 401 khi chưa xác thực, 403 {@code FORBIDDEN_OWNER} khi không phải chủ, 503 khi user-service không khả dụng
     */
    public void requirePayoutAccountOwner(String pathUserId, String authorization) {
        if (isAdmin()) return;
        requireToken(authorization);
        if ("me".equals(pathUserId)) return;
        String own;
        try {
            var eligibility = users.getOrganizerEligibility(authorization);
            own = eligibility == null ? null : eligibility.userId();
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
        }
        if (own == null || !own.equals(pathUserId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_OWNER");
    }

    /**
     * Thanh toán của một đơn đặt chỗ chỉ người mua đơn đó hoặc admin được xem. Người không phải chủ đơn nhận 404 để
     * không lộ việc đơn có tồn tại.
     *
     * @param bookingId mã đơn đặt chỗ
     * @param authorization header Authorization của yêu cầu
     * @throws ResponseStatusException 401 khi chưa xác thực, 404 {@code BOOKING_NOT_FOUND} khi không phải chủ đơn, 503 khi booking-service không khả dụng
     */
    public void requireBookingBuyer(String bookingId, String authorization) {
        if (isAdmin()) return;
        requireToken(authorization);
        String subject = subject();
        String customer;
        try {
            var booking = bookings.getBooking(bookingId, authorization);
            customer = booking == null ? null : booking.customerId();
        } catch (FeignException.NotFound | FeignException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE");
        }
        if (subject == null || !subject.equals(customer)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
    }

    /**
     * Tra cứu hàng loạt phương thức thanh toán chỉ dành cho booking-service (bí mật dùng chung) và admin.
     * So sánh token theo thời gian hằng định.
     *
     * @param internalToken giá trị header {@code X-Internal-Token}
     * @throws ResponseStatusException 403 {@code FORBIDDEN} khi token thiếu, sai hoặc chưa cấu hình
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
     * Yêu cầu có header Authorization.
     *
     * @param authorization giá trị header Authorization
     * @throws ResponseStatusException HTTP 401 {@code UNAUTHENTICATED} khi thiếu
     */
    private static void requireToken(String authorization) {
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    /**
     * Kiểm tra người gọi hiện tại có vai trò {@code ROLE_ADMIN} hay không.
     *
     * @return true nếu là admin
     */
    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Lấy {@code sub} của JWT đang xác thực.
     *
     * @return mã người dùng, hoặc null nếu không có JWT
     */
    private static String subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }
}
