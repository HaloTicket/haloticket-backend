package com.haloticket.notification_service.exception;

/** Ngoại lệ không tìm thấy tài nguyên: mã {@code RESOURCE_NOT_FOUND}, HTTP 404. */
public class ResourceNotFoundException extends NotificationException {
    /**
     * Tạo ngoại lệ.
     *
     * @param message mô tả lỗi trả về cho client
     */
    public ResourceNotFoundException(String message) {
        super(NotificationErrorCode.RESOURCE_NOT_FOUND, message, 404);
    }
}
