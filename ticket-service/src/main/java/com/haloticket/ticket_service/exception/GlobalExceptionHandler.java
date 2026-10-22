package com.haloticket.ticket_service.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private String code;
        private String message;
        private Instant timestamp;
    }

    @ExceptionHandler(CheckinException.class)
    public ResponseEntity<ErrorResponse> handleCheckinException(CheckinException exception) {
        log.warn("Checkin business error [{}]: {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                exception.getCode(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Invalid argument: {}", exception.getMessage());
        String code = "INVALID_REQUEST";
        String msg = exception.getMessage() != null ? exception.getMessage() : "";
        if (msg.contains("Ticket not found")) code = "TICKET_NOT_FOUND";
        else if (msg.contains("does not belong to this event")) code = "WRONG_EVENT";
        else if (msg.contains("does not belong to this showtime")) code = "WRONG_SHOWTIME";
        return ResponseEntity.badRequest().body(new ErrorResponse(
                code, exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException exception) {
        log.warn("Concurrency conflict: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "CONCURRENCY_CONFLICT", "Vé đã được thao tác hoặc xử lý ở một nơi khác (Double-scanning). Vui lòng thử lại.", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception in ticket-service: ", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "INTERNAL_SERVER_ERROR", exception.getMessage() != null ? exception.getMessage() : "An unexpected error occurred", Instant.now()));
    }
}
