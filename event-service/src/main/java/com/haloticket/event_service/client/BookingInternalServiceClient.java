package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "booking-service")
public interface BookingInternalServiceClient {

    @GetMapping("/api/internal/bookings/events/{eventId}/stats")
    BookingStatsResponse getEventBookingStats(@PathVariable("eventId") String eventId);
}
