package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "ticket-service", path = "/api/tickets")
public interface TicketServiceClient {

    @PostMapping("/types/sync/{eventId}")
    Object syncTicketTypesForEvent(@PathVariable("eventId") String eventId,
                                   @RequestParam(value = "showtimeId", required = false) String showtimeId,
                                   @RequestBody List<EventCreateRequest.TicketCreateRequest> requests);

    @GetMapping("/types/event/{eventId}")
    Object getTicketTypesByEventId(@PathVariable("eventId") String eventId);
}
