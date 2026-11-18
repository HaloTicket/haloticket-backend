package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Một suất diễn của sự kiện: thời gian diễn và thời gian mở bán. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventShowtimeDto {
    private String id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
}
