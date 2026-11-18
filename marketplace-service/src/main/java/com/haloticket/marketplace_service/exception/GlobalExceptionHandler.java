package com.haloticket.marketplace_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Bộ xử lý ngoại lệ toàn cục của {@code marketplace-service}: chuyển ngoại lệ thành phản hồi JSON
 * gồm {@code timestamp}, {@code status}, {@code error} và {@code message}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Xử lý {@code ResponseStatusException}: giữ nguyên mã HTTP và dùng lý do làm nội dung lỗi.
     *
     * @param ex ngoại lệ do controller hoặc service ném ra
     * @return phản hồi JSON với mã trạng thái của ngoại lệ
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getReason());
        body.put("message", ex.getReason());
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    /**
     * Xử lý mọi ngoại lệ còn lại: trả HTTP 500 với thông báo chung để không lộ chi tiết nội bộ.
     *
     * @param ex ngoại lệ không lường trước
     * @return phản hồi JSON với mã 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", "An unexpected error occurred");
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
