package com.haloticket.notification_service.exception;

import java.time.Instant;

/** Nội dung phản hồi lỗi: mã lỗi, thông điệp và thời điểm xảy ra. */
public record NotificationExceptionResponse(String code, String message, Instant timestamp) {
}
