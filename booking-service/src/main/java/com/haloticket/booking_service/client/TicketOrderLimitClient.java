package com.haloticket.booking_service.client;

import com.haloticket.booking_service.dto.response.TicketTypeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Reads minPerOrder / maxPerOrder for OrderLimitValidator. Separate client so it can have its own short timeouts. */
@FeignClient(name = "ticket-service", contextId = "ticketOrderLimitClient")
public interface TicketOrderLimitClient {

    @GetMapping("/api/tickets/types/{id}")
    TicketTypeDto getTicketTypeById(@PathVariable("id") String id);
}
