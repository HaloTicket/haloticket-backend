package com.haloticket.event_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Dữ liệu một suất diễn: tên, thời gian diễn và thời gian mở bán. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventShowtimeRequest {
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
}
