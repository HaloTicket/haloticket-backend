package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

@FeignClient(name = "booking-service", path = "/api/internal/bookings/events")
public interface BookingBuyerClient {
    @GetMapping("/{eventId}/notification-buyers")
    List<String> buyerIds(@RequestHeader(AnnouncementDirectoryClient.INTERNAL_TOKEN) String token,
                          @PathVariable String eventId, @RequestParam Instant cutoff,
                          @RequestParam String after, @RequestParam int limit);
}
