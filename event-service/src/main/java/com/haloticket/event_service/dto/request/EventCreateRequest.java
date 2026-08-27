package com.haloticket.event_service.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EventCreateRequest {
    private String title;
    private String description;
    private String categoryId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
