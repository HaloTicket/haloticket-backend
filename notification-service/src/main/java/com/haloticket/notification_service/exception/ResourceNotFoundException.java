package com.haloticket.notification_service.exception;

public class ResourceNotFoundException extends NotificationException {
    public ResourceNotFoundException(String message) {
        super(NotificationErrorCode.RESOURCE_NOT_FOUND, message, 404);
    }
}
