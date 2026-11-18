package com.haloticket.user_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Bộ xử lý ngoại lệ chung của user-service: đổi ngoại lệ thành phản hồi JSON gồm timestamp, status, error và message. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Giữ nguyên mã HTTP và lý do của {@code ResponseStatusException}.
     *
     * @param ex ngoại lệ do controller hoặc service ném ra
     * @return phản hồi JSON với mã HTTP của ngoại lệ
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("ResponseStatusException: status={}, reason={}", ex.getStatusCode(), ex.getReason());
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getReason());
        body.put("message", ex.getReason());
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    // giá trị enum không hợp lệ hoặc JSON sai định dạng là lỗi của bên gọi, không phải lỗi máy chủ
    /**
     * Trả HTTP 400 {@code INVALID_BODY} khi nội dung yêu cầu hoặc kiểu tham số không đọc được.
     *
     * @param ex ngoại lệ đọc nội dung hoặc ép kiểu tham số
     * @return phản hồi JSON HTTP 400
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception ex) {
        log.warn("Bad request input: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "INVALID_BODY");
        body.put("message", "INVALID_BODY");
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * Chốt chặn cuối: ghi log lỗi chưa được xử lý và trả HTTP 500 kèm thông điệp của ngoại lệ.
     *
     * @param ex ngoại lệ bất kỳ chưa có bộ xử lý riêng
     * @return phản hồi JSON HTTP 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) {
        log.error("Unhandled exception in user-service: ", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred");
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
