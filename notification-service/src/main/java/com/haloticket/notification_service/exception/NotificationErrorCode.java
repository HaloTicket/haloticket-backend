package com.haloticket.notification_service.exception;

/** Mã lỗi nghiệp vụ của notification-service, trả về trong trường {@code code} của phản hồi lỗi. */
public enum NotificationErrorCode {
    TEMPLATE_NOT_FOUND,
    TEMPLATE_INACTIVE,
    NOTIFICATION_NOT_FOUND,
    INVALID_NOTIFICATION_EVENT,
    RECIPIENT_EMAIL_REQUIRED,
    VARIABLES_SERIALIZATION_FAILED,
    EMAIL_DELIVERY_FAILED,
    EMAIL_TEMPLATE_NOT_FOUND,
    INVALID_REQUEST,
    FORBIDDEN,
    RESOURCE_NOT_FOUND,
    UNAUTHORIZED
}
