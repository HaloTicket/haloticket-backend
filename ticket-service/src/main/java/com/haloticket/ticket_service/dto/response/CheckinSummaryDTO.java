package com.haloticket.ticket_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Số vé của một sự kiện có thể vào cửa và số đã vào. {@code total} chỉ tính vé vẫn thuộc người giữ
 * (VALID, USED, hoặc đang khóa vì bán lại hoặc chuyển nhượng); vé đã thu hồi hoặc đã chuyển đi không được tính.
 */
public record CheckinSummaryDTO(String eventId, long total, long checkedIn, List<Group> byShowtime, List<Group> byTicketType,
                                LocalDateTime generatedAt) {

    /** Một nhóm trong thống kê: một suất diễn hoặc một loại vé ({@code id} và {@code name} là {@code null} với loại còn lại). */
    public record Group(String id, String name, long total, long checkedIn) {}
}
