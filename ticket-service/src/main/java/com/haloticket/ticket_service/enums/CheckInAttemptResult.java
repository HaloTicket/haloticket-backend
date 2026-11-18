package com.haloticket.ticket_service.enums;

/**
 * Kết quả của một lần quét vé, lưu ở cột {@code checkin_attempts.result} (varchar có ràng buộc CHECK mà Hibernate không bao giờ cập nhật:
 * thêm giá trị mới cần một migration SQL mới, xem {@code haloticket-infrastructure/postgres/migrations}).
 */
public enum CheckInAttemptResult {
    SUCCESS,
    FAILED_SIGNATURE,
    FAILED_ALREADY_IN,
    OUTSIDE_CHECKIN_WINDOW,
    TICKET_NOT_FOUND,
    WRONG_EVENT,
    WRONG_SHOWTIME,
    TICKET_LOCKED,
    WINDOW_UNVERIFIED
}
