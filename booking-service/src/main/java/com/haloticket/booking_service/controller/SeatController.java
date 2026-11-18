package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.dto.request.SeatHoldRequest;
import com.haloticket.booking_service.service.SeatHoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * API giữ và nhả ghế tạm thời trong lúc người dùng chọn chỗ.
 */
@Slf4j
@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private static final String ANONYMOUS_HOLD_PREFIX = "session_";

    private final SeatHoldService seatHoldService;

    /**
     * Giữ một ghế cho người dùng.
     * @param request sự kiện, suất chiếu, ghế và mã người giữ
     * @param jwt token người dùng, có thể null với khách chưa đăng nhập
     * @return 200 khi giữ được, 409 khi ghế đã bị giữ hoặc không còn
     */
    @PostMapping("/hold")
    public ResponseEntity<String> holdSeat(@RequestBody SeatHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        String holder = holderOf(request.getUserId(), jwt);
        boolean success = seatHoldService.holdSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), holder);
        if (success) {
            return ResponseEntity.ok("Seat held successfully");
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Seat is already held or unavailable");
        }
    }

    /**
     * Nhả ghế đang giữ của người dùng.
     * @param request sự kiện, suất chiếu, ghế và mã người giữ
     * @param jwt token người dùng, có thể null với khách chưa đăng nhập
     * @return thông báo nhả thành công
     */
    @PostMapping("/release")
    public ResponseEntity<String> releaseSeat(@RequestBody SeatHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        String holder = holderOf(request.getUserId(), jwt);
        seatHoldService.releaseSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), holder);
        return ResponseEntity.ok("Seat released successfully");
    }

    /**
     * Người đã đăng nhập giữ và nhả ghế dưới subject của token, bất kể body ghi gì (nếu không, ai cũng có thể
     * nhả ghế của người khác bằng cách gửi id của họ). Người chưa đăng nhập dùng id trình duyệt ẩn danh "session_..." do web tạo;
     * mọi id khác mà không có token đều bị từ chối.
     * @param requested id người giữ do client gửi
     * @param jwt token người dùng, có thể null
     * @return id người giữ thực sự được dùng
     * @throws ResponseStatusException HTTP 401 khi không có token và id không phải dạng ẩn danh
     */
    private String holderOf(String requested, Jwt jwt) {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            if (requested != null && !requested.equals(jwt.getSubject()) && !requested.startsWith(ANONYMOUS_HOLD_PREFIX)) {
                log.warn("[IDENTITY_MISMATCH] seat hold userId in the body differs from the token subject; the token wins");
            }
            return jwt.getSubject();
        }
        if (requested != null && requested.startsWith(ANONYMOUS_HOLD_PREFIX)) return requested;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}
