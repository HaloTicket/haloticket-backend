package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tình trạng đồng bộ vé sang ticket-service. {@code status} nhận một trong: {@code SYNCED}, {@code FAILED}, {@code UNAVAILABLE} (không đọc được ticket-service)
 * hoặc {@code null} (chưa từng theo dõi, với sự kiện cũ).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketsSyncDto {
    private String status;
    private String error;
    private LocalDateTime syncedAt;
}
