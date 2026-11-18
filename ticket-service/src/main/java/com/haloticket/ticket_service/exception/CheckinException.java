package com.haloticket.ticket_service.exception;

import lombok.Getter;

/**
 * Lỗi nghiệp vụ khi quét check-in bị từ chối; mang theo mã lỗi để client hiển thị đúng lý do.
 */
@Getter
public class CheckinException extends RuntimeException {
    private final String code;

    /**
     * @param code mã lỗi nghiệp vụ (ví dụ {@code TICKET_NOT_FOUND})
     * @param message nội dung mô tả lỗi
     */
    public CheckinException(String code, String message) {
        super(message);
        this.code = code;
    }
}
