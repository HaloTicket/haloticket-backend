package com.haloticket.notification_service.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<NotificationExceptionResponse> handleNotificationException(NotificationException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new NotificationExceptionResponse(
                exception.getCode().name(), exception.getMessage(), Instant.now()));
    }

    /** A deliberate status (401/403/404/503...) must reach the caller as it is; the catch-all below would turn it into a 500. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<NotificationExceptionResponse> handleStatus(org.springframework.web.server.ResponseStatusException exception) {
        String code = exception.getReason() != null ? exception.getReason() : String.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(exception.getStatusCode()).body(new NotificationExceptionResponse(code, code, Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<NotificationExceptionResponse> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new NotificationExceptionResponse(
                "INVALID_REQUEST", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<NotificationExceptionResponse> handleUnexpectedException(Exception exception) {
        return ResponseEntity.internalServerError().body(new NotificationExceptionResponse(
                "INTERNAL_SERVER_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
