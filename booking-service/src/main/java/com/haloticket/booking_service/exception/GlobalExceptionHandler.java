package com.haloticket.booking_service.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @ExceptionHandler(VoucherNotApplicableException.class)
    public ResponseEntity<ErrorResponse> handleVoucherNotApplicable(VoucherNotApplicableException exception) {
        log.info("Voucher refused: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "VOUCHER_NOT_APPLICABLE", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Invalid argument: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "INVALID_REQUEST", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException exception) {
        log.warn("Conflict: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "CONFLICT", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(org.springframework.web.server.ResponseStatusException exception) {
        log.warn("Response status exception: {} - {}", exception.getStatusCode(), exception.getReason());
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(
                exception.getReason() != null ? exception.getReason() : exception.getStatusCode().toString(),
                exception.getReason() != null ? exception.getReason() : exception.getMessage(),
                Instant.now()));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                "NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception in booking-service: ", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "INTERNAL_SERVER_ERROR", exception.getMessage() != null ? exception.getMessage() : "An unexpected error occurred", Instant.now()));
    }
}
