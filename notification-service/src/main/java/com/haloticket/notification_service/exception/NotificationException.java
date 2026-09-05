package com.haloticket.notification_service.exception;

import lombok.Getter;

@Getter
public class NotificationException extends RuntimeException {
    private final NotificationErrorCode code;
    private final int status;

    public NotificationException(NotificationErrorCode code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public NotificationException(NotificationErrorCode code, String message, int status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}
