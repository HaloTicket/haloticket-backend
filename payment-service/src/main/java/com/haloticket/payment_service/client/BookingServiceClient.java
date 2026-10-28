package com.haloticket.payment_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** Only to learn who a booking belongs to; the caller's token is forwarded. */
@FeignClient(name = "booking-service", path = "/api/bookings")
public interface BookingServiceClient {

    @GetMapping("/{id}")
    BookingOwner getBooking(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BookingOwner(String id, String customerId) {}
}
