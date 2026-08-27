package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.EventStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EventResponse {
    private String id;
    private String title;
    private EventStatus eventStatus;
    private LocalDateTime startTime;
}
