package com.haloticket.booking_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** Dữ liệu sự kiện lấy từ event-service, chỉ gồm các trường booking-service cần. */
@Data
public class EventResponse {
    private String id;
    private String title;
    private String locationName;
    private String address;
    private LocalDateTime startTime;
    private List<EventShowtimeDto> showtimes;
}
