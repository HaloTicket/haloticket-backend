package com.haloticket.notification_service.exception;

import lombok.Getter;

/** Ngoại lệ nghiệp vụ gốc của notification-service, mang mã lỗi và mã HTTP để {@code GlobalExceptionHandler} trả về. */
@Getter
public class NotificationException extends RuntimeException {
    private final NotificationErrorCode code;
    private final int status;

    /**
     * Tạo ngoại lệ không kèm nguyên nhân.
     *
     * @param code mã lỗi nghiệp vụ
     * @param message mô tả lỗi
     * @param status mã trạng thái HTTP
     */
    public NotificationException(NotificationErrorCode code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * Tạo ngoại lệ kèm nguyên nhân gốc.
     *
     * @param code mã lỗi nghiệp vụ
     * @param message mô tả lỗi
     * @param status mã trạng thái HTTP
     * @param cause ngoại lệ gốc
     */
    public NotificationException(NotificationErrorCode code, String message, int status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}
