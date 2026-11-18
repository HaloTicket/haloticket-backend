package com.haloticket.event_service.exception;

import java.util.Map;

/**
 * ticket-service từ chối các loại vé bằng HTTP 422 (ví dụ {@code QUANTITY_BELOW_SOLD_AND_HELD}): lỗi nằm ở dữ liệu nhà tổ chức nhập, nên không bao giờ thử lại
 * và được trả cho người gọi dưới dạng 422 với cùng mã lỗi và chi tiết.
 */
public class TicketSyncRejectedException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;
    private final String eventId;

    /**
     * Tạo ngoại lệ từ phản hồi 422 của ticket-service.
     * @param code mã lỗi do ticket-service trả về, cũng là thông điệp của ngoại lệ
     * @param details chi tiết lỗi (ví dụ loại vé, số đã bán); {@code null} được xem là rỗng
     * @param eventId mã sự kiện đang đồng bộ, có thể {@code null} khi chưa biết
     */
    public TicketSyncRejectedException(String code, Map<String, Object> details, String eventId) {
        super(code);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.eventId = eventId;
    }

    /** Mã lỗi do ticket-service trả về. */
    public String getCode() { return code; }
    /** Chi tiết lỗi dạng map bất biến, không bao giờ {@code null}. */
    public Map<String, Object> getDetails() { return details; }
    /** Mã sự kiện đang đồng bộ; {@code null} nếu chưa gán qua {@code forEvent}. */
    public String getEventId() { return eventId; }

    /**
     * Tạo bản sao ngoại lệ có gắn mã sự kiện, giữ nguyên mã lỗi và chi tiết.
     * @param id mã sự kiện
     * @return ngoại lệ mới
     */
    public TicketSyncRejectedException forEvent(String id) {
        return new TicketSyncRejectedException(code, details, id);
    }
}
