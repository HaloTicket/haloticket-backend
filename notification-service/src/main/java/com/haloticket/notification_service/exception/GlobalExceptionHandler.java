package com.haloticket.notification_service.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** Bộ xử lý ngoại lệ toàn cục: chuyển ngoại lệ thành {@code NotificationExceptionResponse} kèm mã HTTP phù hợp. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * Trả về mã HTTP và mã lỗi của ngoại lệ nghiệp vụ.
     *
     * @param exception ngoại lệ nghiệp vụ
     * @return phản hồi lỗi
     */
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<NotificationExceptionResponse> handleNotificationException(NotificationException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new NotificationExceptionResponse(
                exception.getCode().name(), exception.getMessage(), Instant.now()));
    }

    /**
     * Giữ nguyên mã trạng thái chủ ý (401/403/404/503...) để tới được client; nếu không, bộ bắt lỗi chung bên dưới sẽ biến nó thành 500.
     *
     * @param exception ngoại lệ trạng thái HTTP
     * @return phản hồi lỗi với đúng mã trạng thái đó
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<NotificationExceptionResponse> handleStatus(org.springframework.web.server.ResponseStatusException exception) {
        String code = exception.getReason() != null ? exception.getReason() : String.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(exception.getStatusCode()).body(new NotificationExceptionResponse(code, code, Instant.now()));
    }

    /**
     * Chuyển {@code IllegalArgumentException} thành HTTP 400 với mã {@code INVALID_REQUEST}.
     *
     * @param exception ngoại lệ tham số sai
     * @return phản hồi lỗi
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<NotificationExceptionResponse> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new NotificationExceptionResponse(
                "INVALID_REQUEST", exception.getMessage(), Instant.now()));
    }

    /**
     * Bắt mọi lỗi còn lại: trả HTTP 500 với thông điệp chung, không lộ chi tiết nội bộ.
     *
     * @param exception lỗi không lường trước
     * @return phản hồi lỗi
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<NotificationExceptionResponse> handleUnexpectedException(Exception exception) {
        return ResponseEntity.internalServerError().body(new NotificationExceptionResponse(
                "INTERNAL_SERVER_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
