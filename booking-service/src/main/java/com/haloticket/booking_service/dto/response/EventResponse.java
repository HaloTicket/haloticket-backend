package com.haloticket.booking_service.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class EventResponse {
    private String id;
    private String title;
    private List<EventShowtimeDto> showtimes;
}
