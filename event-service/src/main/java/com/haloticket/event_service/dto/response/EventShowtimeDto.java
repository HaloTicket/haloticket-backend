package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
