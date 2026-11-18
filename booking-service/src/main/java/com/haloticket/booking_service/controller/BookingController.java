package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventAccessClient;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.service.BookingCancellationService;
import com.haloticket.booking_service.service.BookingReissueService;
import com.haloticket.booking_service.service.BookingService;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * API đặt vé cho người mua: tạo đơn, xem đơn, hủy đơn, tra cứu theo mã đơn và yêu cầu phát hành lại vé.
 */
@Slf4j
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingCancellationService bookingCancellationService;
    private final EventAccessClient eventAccess;

    @Autowired(required = false)
    private BookingReissueService reissueService;

    /**
     * Tạo đơn đặt vé mới. Người mua luôn là chủ token, nên {@code customerId} trong body bị ghi đè bằng subject của JWT.
     * @param request thông tin đơn cần tạo
     * @param jwt token của người đang đăng nhập
     * @return đơn vừa tạo với HTTP 201
     * @throws ResponseStatusException HTTP 401 khi chưa đăng nhập
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(@RequestBody BookingCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        // người mua là người đang đăng nhập: không tin customerId gửi trong body
        String subject = subjectOf(jwt);
        if (request.getCustomerId() != null && !request.getCustomerId().equals(subject)) {
            log.warn("[IDENTITY_MISMATCH] booking customerId in the body differs from the token subject; the token wins");
        }
        request.setCustomerId(subject);
        return new ResponseEntity<>(bookingService.createBooking(request), HttpStatus.CREATED);
    }

    /**
     * Mã đơn là UUID nhưng cũng xuất hiện trong URL và email nên không phải bí mật. Đơn được hiển thị cho người mua,
     * thành viên của sự kiện có quyền ORDERS_VIEW và quản trị viên nền tảng; người khác nhận 404
     * (giống hệt trường hợp đơn không tồn tại).
     */
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        BookingResponse booking = bookingService.getBookingById(id);
        String subject = subjectOf(jwt);
        if (!subject.equals(booking.getCustomerId())) {
            try {
                eventAccess.require(booking.getEventId(), "ORDERS_VIEW", authorization);
            } catch (ResponseStatusException denied) {
                if (denied.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                        || denied.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
                }
                throw denied;
            }
        }
        return ResponseEntity.ok(booking);
    }

    /**
     * Hủy đơn của chính người đang đăng nhập.
     * @param id mã đơn cần hủy
     * @param jwt token của người mua
     * @return đơn sau khi hủy
     * @throws ResponseStatusException HTTP 401 khi thiếu token hoặc subject
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(bookingCancellationService.cancelOwnBooking(id, jwt.getSubject()));
    }

    /**
     * Tra cứu chi tiết đơn theo mã đơn, chỉ trong phạm vi đơn của người đang đăng nhập.
     * @param bookingCode mã đơn hiển thị cho khách
     * @param jwt token của người mua
     * @return chi tiết đơn
     * @throws ResponseStatusException HTTP 401 khi thiếu token hoặc subject
     */
    @GetMapping("/by-code/{bookingCode}")
    public ResponseEntity<BookingDetailResponse> getBookingByCode(
            @PathVariable String bookingCode,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(bookingService.getBookingByCode(bookingCode, jwt.getSubject()));
    }

    /**
     * Nút "Tải lại" ở trang đơn hàng: yêu cầu ticket-service phát hành lại vé của đơn đã thanh toán của chính người gọi.
     * Trả {"result": REQUESTED | ALREADY_ISSUED | TOO_SOON | NOT_PAID, "retryAfterSeconds"?}.
     */
    @PostMapping("/by-code/{bookingCode}/reissue")
    public ResponseEntity<Map<String, Object>> reissueTickets(@PathVariable String bookingCode, @AuthenticationPrincipal Jwt jwt) {
        String subject = subjectOf(jwt);
        if (reissueService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REISSUE_UNAVAILABLE");
        BookingReissueService.Outcome outcome = reissueService.reissueForCustomer(bookingCode, subject);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", outcome.result().name());
        if (outcome.retryAfterSeconds() != null) body.put("retryAfterSeconds", outcome.retryAfterSeconds());
        return ResponseEntity.ok(body);
    }

    /**
     * Lấy subject từ JWT.
     * @param jwt token cần đọc
     * @return subject không rỗng
     * @throws ResponseStatusException HTTP 401 khi thiếu token hoặc subject rỗng
     */
    private static String subjectOf(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        return jwt.getSubject();
    }
}
