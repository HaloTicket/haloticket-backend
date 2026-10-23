package com.haloticket.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/** general-admission stock holds owned by ticket-service. Separate client so it can have its own short timeouts. */
@FeignClient(name = "ticket-service", contextId = "ticketStockClient")
public interface TicketStockClient {

    @PostMapping("/api/internal/tickets/stock-reservations")
    void reserve(@RequestHeader("X-Internal-Token") String token, @RequestBody Map<String, Object> body);

    @PostMapping("/api/internal/tickets/stock-reservations/{bookingId}/release")
    void release(@RequestHeader("X-Internal-Token") String token, @PathVariable("bookingId") String bookingId);
}
