package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.List;

@FeignClient(name = "booking-service")
public interface BookingInternalServiceClient {

    @GetMapping("/api/internal/bookings/events/{eventId}/stats")
    BookingStatsResponse getEventBookingStats(@PathVariable("eventId") String eventId);

    @PostMapping("/api/internal/bookings/report-facts")
    List<ReportBookingFact> getReportFacts(@RequestHeader("Authorization") String authorization,
            @RequestBody List<String> bookingIds);
}
