package com.haloticket.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** general-admission stock holds owned by ticket-service. Separate client so it can have its own short timeouts. */
@FeignClient(name = "ticket-service", contextId = "ticketStockClient")
public interface TicketStockClient {

    @PostMapping("/api/internal/tickets/stock-reservations")
    void reserve(@RequestHeader("X-Internal-Token") String token, @RequestBody Map<String, Object> body);

    @PostMapping("/api/internal/tickets/stock-reservations/{bookingId}/release")
    void release(@RequestHeader("X-Internal-Token") String token, @PathVariable("bookingId") String bookingId);

    /** Takes back a closed hold for a late payment. 409 TICKET_SOLD_OUT / RESERVATION_NOT_REOPENABLE change nothing. */
    @PostMapping("/api/internal/tickets/stock-reservations/{bookingId}/reopen")
    void reopen(@RequestHeader("X-Internal-Token") String token, @PathVariable("bookingId") String bookingId,
                @RequestBody Map<String, Object> body);

    /** Body {"bookingIds":[...up to 500]}; answer {"counts":{"<bookingId>":n}}, a missing id means 0. */
    @PostMapping("/api/internal/tickets/issued-counts")
    Map<String, Map<String, Integer>> issuedCounts(@RequestHeader("X-Internal-Token") String token,
                                                   @RequestBody Map<String, Object> body);

    /** Repairs one ticket type's remaining stock. 404 / 409 / 422 change nothing. */
    @PostMapping("/api/internal/tickets/stock-reconciliation/{ticketTypeId}/fix")
    Map<String, Object> fixStock(@RequestHeader("X-Internal-Token") String token, @PathVariable("ticketTypeId") String ticketTypeId);

    @GetMapping("/api/internal/tickets/stock-reconciliation")
    List<Map<String, Object>> stockReconciliation(@RequestHeader("X-Internal-Token") String token,
                                                  @RequestParam("limit") int limit);
}
