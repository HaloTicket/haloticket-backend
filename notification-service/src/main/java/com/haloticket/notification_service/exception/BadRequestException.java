package com.haloticket.notification_service.exception;

/** Ngoại lệ yêu cầu không hợp lệ: mã {@code INVALID_REQUEST}, HTTP 400. */
public class BadRequestException extends NotificationException {
    /**
     * Tạo ngoại lệ.
     *
     * @param message mô tả lỗi trả về cho client
     */
    public BadRequestException(String message) {
        super(NotificationErrorCode.INVALID_REQUEST, message, 400);
    }
}
