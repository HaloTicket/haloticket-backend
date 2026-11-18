package com.haloticket.user_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** Endpoint kiểm tra sống của user-service. */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * Trả về tên dịch vụ, trạng thái {@code UP} và thời điểm hiện tại (mili giây).
     *
     * @return bản đồ thông tin sức khỏe
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> status = new HashMap<>();
        status.put("service", "user-service");
        status.put("status", "UP");
        status.put("timestamp", String.valueOf(System.currentTimeMillis()));

        return ResponseEntity.ok(status);
    }
}
