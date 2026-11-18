package com.haloticket.booking_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

/** Suất chiếu của sự kiện kèm thời gian diễn ra và thời gian mở bán. */
@Data
public class EventShowtimeDto {
    private String id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
}
