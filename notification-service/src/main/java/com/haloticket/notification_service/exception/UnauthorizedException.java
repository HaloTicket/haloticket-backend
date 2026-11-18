package com.haloticket.notification_service.exception;

/** Ngoại lệ chưa xác thực: mã {@code UNAUTHORIZED}, HTTP 401. */
public class UnauthorizedException extends NotificationException {
    /**
     * Tạo ngoại lệ.
     *
     * @param message mô tả lỗi trả về cho client
     */
    public UnauthorizedException(String message) {
        super(NotificationErrorCode.UNAUTHORIZED, message, 401);
    }
}
