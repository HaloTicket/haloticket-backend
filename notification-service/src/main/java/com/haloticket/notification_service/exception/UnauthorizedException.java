package com.haloticket.notification_service.exception;

public class UnauthorizedException extends NotificationException {
    public UnauthorizedException(String message) {
        super(NotificationErrorCode.UNAUTHORIZED, message, 401);
    }
}
