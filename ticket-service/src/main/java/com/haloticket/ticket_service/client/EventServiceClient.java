package com.haloticket.ticket_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "event-service", url = "${EVENT_SERVICE_URL:}")
public interface EventServiceClient {

    @GetMapping("/api/events/{id}")
    EventDetailsResponse getEventById(@PathVariable("id") String id);

    record EventDetailsResponse(
            String id,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<ShowtimeDetailsDto> showtimes
    ) {}

    record ShowtimeDetailsDto(
            String id,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {}
}
