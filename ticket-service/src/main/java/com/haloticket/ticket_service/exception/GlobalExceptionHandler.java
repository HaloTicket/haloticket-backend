package com.haloticket.ticket_service.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Bộ xử lý ngoại lệ toàn cục của ticket-service, đổi mọi ngoại lệ thành {@code ErrorResponse} với mã HTTP phù hợp.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Nội dung lỗi trả về cho client: mã lỗi, thông điệp và thời điểm xảy ra.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private String code;
        private String message;
        private Instant timestamp;
    }

    /**
     * Chuyển lỗi nghiệp vụ check-in thành phản hồi HTTP 400 kèm mã lỗi của ngoại lệ.
     *
     * @param exception lỗi check-in bị từ chối
     * @return phản hồi 400 chứa mã và thông điệp lỗi
     */
    @ExceptionHandler(CheckinException.class)
    public ResponseEntity<ErrorResponse> handleCheckinException(CheckinException exception) {
        log.warn("Checkin business error [{}]: {}", exception.getCode(), exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                exception.getCode(), exception.getMessage(), Instant.now()));
    }

    /**
     * Giữ nguyên mã trạng thái cố ý (401/403/404/503...) để tới được client; nếu không, bộ xử lý bắt-tất-cả bên dưới sẽ đổi thành 500.
     *
     * @param exception ngoại lệ mang mã trạng thái HTTP
     * @return phản hồi với đúng mã trạng thái, lấy lý do làm mã lỗi
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        String code = exception.getReason() != null ? exception.getReason() : status != null ? status.name() : "ERROR";
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(code, code, Instant.now()));
    }

    /**
     * Chuyển {@code IllegalArgumentException} thành HTTP 400. Mã lỗi được suy ra từ nội dung thông điệp:
     * {@code TICKET_NOT_FOUND}, {@code WRONG_EVENT}, {@code WRONG_SHOWTIME}, còn lại là {@code INVALID_REQUEST}.
     *
     * @param exception tham số không hợp lệ
     * @return phản hồi 400 chứa mã và thông điệp lỗi
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Invalid argument: {}", exception.getMessage());
        String code = "INVALID_REQUEST";
        String msg = exception.getMessage() != null ? exception.getMessage() : "";
        if (msg.contains("Ticket not found")) code = "TICKET_NOT_FOUND";
        else if (msg.contains("does not belong to this event")) code = "WRONG_EVENT";
        else if (msg.contains("does not belong to this showtime")) code = "WRONG_SHOWTIME";
        return ResponseEntity.badRequest().body(new ErrorResponse(
                code, exception.getMessage(), Instant.now()));
    }

    /**
     * Trả HTTP 409 khi hai thao tác cùng cập nhật một vé (ví dụ quét trùng) và khóa lạc quan bị xung đột.
     *
     * @param exception lỗi xung đột phiên bản
     * @return phản hồi 409 với mã {@code CONCURRENCY_CONFLICT}
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException exception) {
        log.warn("Concurrency conflict: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "CONCURRENCY_CONFLICT", "Vé đã được thao tác hoặc xử lý ở một nơi khác (Double-scanning). Vui lòng thử lại.", Instant.now()));
    }

    /**
     * Bộ xử lý bắt-tất-cả: ghi log lỗi không lường trước và trả HTTP 500.
     *
     * @param exception ngoại lệ chưa được xử lý ở nơi khác
     * @return phản hồi 500 với mã {@code INTERNAL_SERVER_ERROR}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception in ticket-service: ", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "INTERNAL_SERVER_ERROR", exception.getMessage() != null ? exception.getMessage() : "An unexpected error occurred", Instant.now()));
    }
}
