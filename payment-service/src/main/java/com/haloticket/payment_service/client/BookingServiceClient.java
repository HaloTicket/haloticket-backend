package com.haloticket.payment_service.client;

import com.haloticket.payment_service.dto.response.BookingFinanceFacts;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "booking-service", path = "/api/bookings")
public interface BookingServiceClient {

    @GetMapping("/{id}")
    BookingOwner getBooking(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    @GetMapping("/events/{eventId}/organizer/finance-facts")
    BookingFinanceFacts getFinanceFacts(@PathVariable("eventId") String eventId,
            @RequestHeader("Authorization") String authorization);

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BookingOwner(String id, String customerId) {}
}
