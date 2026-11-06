package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** status: SYNCED | FAILED | UNAVAILABLE (ticket-service could not be read) | null (never tracked, older events). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketsSyncDto {
    private String status;
    private String error;
    private LocalDateTime syncedAt;
}
