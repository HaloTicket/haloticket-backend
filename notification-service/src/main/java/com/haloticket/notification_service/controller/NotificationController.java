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

/**
 * API REST {@code /api/notifications}: quản lý mẫu thông báo, tạo và đọc thông báo của người dùng, đánh dấu trạng thái.
 * Quyền truy cập được kiểm tra bởi interceptor {@code NotificationScopeGuard}, không nằm trong controller.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * Tạo mẫu thông báo mới (chỉ dịch vụ nội bộ hoặc admin).
     *
     * @param request nội dung mẫu: loại, kênh, tiêu đề, thân, trạng thái
     * @return mẫu vừa tạo, HTTP 201
     */
    @PostMapping("/templates")
    public ResponseEntity<NotificationTemplateResponse> createTemplate(
            @RequestBody NotificationTemplateCreateRequest request
    ) {
        return new ResponseEntity<>(notificationService.createTemplate(request), HttpStatus.CREATED);
    }

    /**
     * Liệt kê toàn bộ mẫu thông báo.
     *
     * @return danh sách mẫu
     */
    @GetMapping("/templates")
    public ResponseEntity<List<NotificationTemplateResponse>> getTemplates() {
        return ResponseEntity.ok(notificationService.getTemplates());
    }

    /**
     * Tạo thông báo từ một mẫu đang hoạt động, trạng thái ban đầu {@code PENDING}.
     *
     * @param request mã mẫu, người nhận, biến thay thế và kênh (tùy chọn)
     * @return thông báo vừa tạo, HTTP 201
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> createNotification(@RequestBody NotificationCreateRequest request) {
        return new ResponseEntity<>(notificationService.createNotification(request), HttpStatus.CREATED);
    }

    /**
     * Lấy hộp thư thông báo của một người dùng, mới nhất trước. Chỉ chủ hộp thư (hoặc admin) được xem.
     *
     * @param userId mã người dùng
     * @return danh sách thông báo của người dùng đó
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<List<NotificationResponse>> getUserNotifications(@PathVariable String userId) {
        return ResponseEntity.ok(notificationService.getUserNotifications(userId));
    }

    /**
     * Đánh dấu thông báo đã gửi và ghi thời điểm gửi.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @PatchMapping("/{id}/sent")
    public ResponseEntity<NotificationResponse> markAsSent(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsSent(id));
    }

    /**
     * Đánh dấu thông báo gửi thất bại.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @PatchMapping("/{id}/failed")
    public ResponseEntity<NotificationResponse> markAsFailed(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsFailed(id));
    }

    /**
     * Đánh dấu một thông báo là đã đọc; chỉ chủ thông báo được gọi.
     *
     * @param id mã thông báo
     * @return thông báo sau cập nhật
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(@PathVariable String id) {
        return ResponseEntity.ok(notificationService.markAsRead(id));
    }

    /**
     * Đánh dấu toàn bộ thông báo của người dùng là đã đọc.
     *
     * @param userId mã người dùng
     * @return danh sách thông báo sau cập nhật
     */
    @PatchMapping("/users/{userId}/read-all")
    public ResponseEntity<List<NotificationResponse>> markAllAsRead(@PathVariable String userId) {
        return ResponseEntity.ok(notificationService.markAllAsRead(userId));
    }

    /**
     * Phát thông báo trực tiếp cho một người dùng (lưu DB và đẩy qua STOMP), không cần mẫu.
     * Mặc định: {@code userId} là {@code ADMIN}, {@code type} là {@code GENERAL}, tiêu đề "Thông báo mới".
     *
     * @param body các khóa {@code userId}, {@code type}, {@code title}, {@code message}, {@code variables} (JSON dạng chuỗi)
     * @return thông báo đã lưu
     */
    @PostMapping("/publish")
    public ResponseEntity<NotificationResponse> publishNotification(@RequestBody java.util.Map<String, String> body) {
        String userId = body.getOrDefault("userId", "ADMIN");
        String type = body.getOrDefault("type", "GENERAL");
        String title = body.getOrDefault("title", "Thông báo mới");
        String message = body.getOrDefault("message", "");
        return ResponseEntity.ok(notificationService.publishDirectNotification(userId, type, title, message, body.get("variables")));
    }
}
