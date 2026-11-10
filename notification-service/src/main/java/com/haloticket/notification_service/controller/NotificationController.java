package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;
import com.haloticket.notification_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/templates")
    public ResponseEntity<NotificationTemplateResponse> createTemplate(
            @RequestBody NotificationTemplateCreateRequest request
    ) {
        return new ResponseEntity<>(notificationService.createTemplate(request), HttpStatus.CREATED);
    }

    @GetMapping("/templates")
    public ResponseEntity<List<NotificationTemplateResponse>> getTemplates() {
        return ResponseEntity.ok(notificationService.getTemplates());
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> createNotification(@RequestBody NotificationCreateRequest request) {
        return new ResponseEntity<>(notificationService.createNotification(request), HttpStatus.CREATED);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<List<NotificationResponse>> getUserNotifications(@PathVariable String userId) {
        return ResponseEntity.ok(notificationService.getUserNotifications(userId));
    }

    @PatchMapping("/{id}/sent")
    public ResponseEntity<NotificationResponse> markAsSent(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsSent(id));
    }

    @PatchMapping("/{id}/failed")
    public ResponseEntity<NotificationResponse> markAsFailed(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsFailed(id));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsRead(id));
    }

    @PatchMapping("/users/{userId}/read-all")
    public ResponseEntity<List<NotificationResponse>> markAllAsRead(@PathVariable String userId) {
        return ResponseEntity.ok(notificationService.markAllAsRead(userId));
    }

    @PostMapping("/publish")
    public ResponseEntity<NotificationResponse> publishNotification(@RequestBody java.util.Map<String, String> body) {
        String userId = body.getOrDefault("userId", "ADMIN");
        String type = body.getOrDefault("type", "GENERAL");
        String title = body.getOrDefault("title", "Thông báo mới");
        String message = body.getOrDefault("message", "");
        return ResponseEntity.ok(notificationService.publishDirectNotification(userId, type, title, message, body.get("variables")));
    }
}
