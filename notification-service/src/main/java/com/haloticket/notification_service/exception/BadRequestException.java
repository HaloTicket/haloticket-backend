package com.haloticket.notification_service.exception;

public class BadRequestException extends NotificationException {
    public BadRequestException(String message) {
        super(NotificationErrorCode.INVALID_REQUEST, message, 400);
    }
}
