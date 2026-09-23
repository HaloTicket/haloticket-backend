package com.haloticket.booking_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EventShowtimeDto {
    private String id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
}
