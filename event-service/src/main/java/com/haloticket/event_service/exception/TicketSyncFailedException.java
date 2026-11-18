package com.haloticket.event_service.exception;

/** Không đẩy được các loại vé sang ticket-service (sau số lần thử lại có giới hạn). Bản thân sự kiện vẫn được lưu. */
public class TicketSyncFailedException extends RuntimeException {
    private final String eventId;
    private final String errorCode;

    /**
     * Tạo ngoại lệ với thông điệp cố định {@code TICKETS_SYNC_FAILED}.
     * @param eventId mã sự kiện đã lưu nhưng chưa đồng bộ được vé
     * @param errorCode mã lỗi của lần đồng bộ thất bại
     */
    public TicketSyncFailedException(String eventId, String errorCode) {
        super("TICKETS_SYNC_FAILED");
        this.eventId = eventId;
        this.errorCode = errorCode;
    }

    /** Mã sự kiện đã lưu nhưng chưa đồng bộ được vé. */
    public String getEventId() { return eventId; }
    /** Mã lỗi của lần đồng bộ thất bại. */
    public String getErrorCode() { return errorCode; }
}
