package com.haloticket.notification_service.exception;

import java.time.Instant;

public record NotificationExceptionResponse(String code, String message, Instant timestamp) {
}
