package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.service.EventAnalyticsService;
import com.haloticket.event_service.entity.EventViewLog;
import com.haloticket.event_service.repository.EventViewLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.service.EventAccessService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** API phân tích sự kiện: số liệu cho nhà tổ chức và ghi nhận lượt xem trang sự kiện. */
@RestController
@RequiredArgsConstructor
public class EventAnalyticsController {

    private final EventAnalyticsService analyticsService;
    private final EventViewLogRepository viewLogRepository;
    private final EventAccessService access;

    /**
     * Lấy số liệu phân tích của sự kiện; người gọi phải có quyền {@code ANALYTICS_VIEW} trên sự kiện.
     * @param eventId mã sự kiện
     * @param timeRange khoảng thời gian định sẵn, mặc định {@code 30d}
     * @param from ngày bắt đầu tùy chọn
     * @param to ngày kết thúc tùy chọn
     * @param compare {@code true} để so sánh với kỳ trước
     * @return số liệu phân tích
     */
    @GetMapping("/api/events/{eventId}/analytics")
    public ResponseEntity<EventAnalyticsResponse> getEventAnalytics(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "30d") String timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean compare
    ) {
        access.requireForRequest(eventId, Permission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.getEventAnalytics(eventId, timeRange, from, to, compare));
    }

    /**
     * Ghi nhận một lượt xem sự kiện (không cần đăng nhập). Thiếu {@code sessionId} thì dùng id phiên HTTP; địa chỉ IP lấy từ {@code X-Forwarded-For}, nếu không có thì lấy địa chỉ máy gọi.
     * @param eventId mã sự kiện được xem
     * @param body có thể chứa {@code userId} và {@code sessionId}
     * @param request yêu cầu HTTP, dùng để lấy phiên và địa chỉ IP
     * @return 200 không có thân
     */
    @PostMapping("/api/events/{eventId}/view")
    public ResponseEntity<Void> logEventView(
            @PathVariable String eventId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request
    ) {
        String userId = body != null ? body.get("userId") : null;
        String sessionId = body != null ? body.get("sessionId") : null;
        
        if (sessionId == null) {
            sessionId = request.getSession().getId();
        }
        
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null) {
            ipAddress = request.getRemoteAddr();
        }

        EventViewLog log = EventViewLog.builder()
                .eventId(eventId)
                .userId(userId)
                .sessionId(sessionId)
                .ipAddress(ipAddress)
                .viewedAt(LocalDateTime.now())
                .build();
                
        viewLogRepository.save(log);
        return ResponseEntity.ok().build();
    }
}
