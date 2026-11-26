package com.haloticket.notification_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/** Bộ xử lý ngoại lệ toàn cục: chuyển ngoại lệ thành {@code NotificationExceptionResponse} kèm mã HTTP phù hợp. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<NotificationExceptionResponse> handleMissingRoute() {
        return handleStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<NotificationExceptionResponse> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        String code = "METHOD_NOT_ALLOWED";
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders())
                .body(new NotificationExceptionResponse(code, code, Instant.now()));
    }
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
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<NotificationExceptionResponse> handleStatus(ResponseStatusException exception) {
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
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<NotificationExceptionResponse> handleInvalidBody(Exception exception) {
        return ResponseEntity.badRequest().body(new NotificationExceptionResponse(
                "INVALID_REQUEST", "Invalid request parameters or body", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<NotificationExceptionResponse> handleUnexpectedException(Exception exception) {
        return ResponseEntity.internalServerError().body(new NotificationExceptionResponse(
                "INTERNAL_SERVER_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
