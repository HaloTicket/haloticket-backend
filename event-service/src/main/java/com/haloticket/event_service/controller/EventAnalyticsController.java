package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.service.EventAnalyticsService;
import com.haloticket.event_service.entity.EventViewLog;
import com.haloticket.event_service.repository.EventViewLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
public class EventAnalyticsController {

    private final EventAnalyticsService analyticsService;
    private final EventViewLogRepository viewLogRepository;

    @GetMapping("/api/events/{eventId}/analytics")
    public ResponseEntity<EventAnalyticsResponse> getEventAnalytics(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "30d") String timeRange
    ) {
        return ResponseEntity.ok(analyticsService.getEventAnalytics(eventId, timeRange));
    }

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
