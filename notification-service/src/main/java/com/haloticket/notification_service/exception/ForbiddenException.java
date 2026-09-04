package com.haloticket.notification_service.exception;

public class ForbiddenException extends NotificationException {
    public ForbiddenException(String message) {
        super(NotificationErrorCode.FORBIDDEN, message, 403);
    }
}
