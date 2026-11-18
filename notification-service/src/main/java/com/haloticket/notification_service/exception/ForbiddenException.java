package com.haloticket.notification_service.exception;

/** Ngoại lệ không đủ quyền: mã {@code FORBIDDEN}, HTTP 403. */
public class ForbiddenException extends NotificationException {
    /**
     * Tạo ngoại lệ.
     *
     * @param message mô tả lỗi trả về cho client
     */
    public ForbiddenException(String message) {
        super(NotificationErrorCode.FORBIDDEN, message, 403);
    }
}
