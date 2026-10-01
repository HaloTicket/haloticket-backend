package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.AdminEventReportResponse;
import com.haloticket.event_service.service.AdminEventReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/events/admin/reports")
@RequiredArgsConstructor
public class AdminEventReportController {
    private final AdminEventReportService reportService;

    @GetMapping
    public ResponseEntity<AdminEventReportResponse> report(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String organizerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "revenue") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String eventId) {
        return ResponseEntity.ok(reportService.report(authorization, from, to, q, categoryId, organizerId, status,
                sort, page, size, eventId, false));
    }

    @GetMapping("/export")
    public ResponseEntity<AdminEventReportResponse> export(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String organizerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "revenue") String sort,
            @RequestParam(required = false) String eventId) {
        return ResponseEntity.ok(reportService.report(authorization, from, to, q, categoryId, organizerId, status,
                sort, 1, 10, eventId, true));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> reportError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "Yêu cầu báo cáo không hợp lệ." : exception.getReason()));
    }
}
