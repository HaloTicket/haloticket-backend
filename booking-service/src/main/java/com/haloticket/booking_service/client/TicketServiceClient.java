package com.haloticket.booking_service.client;

import com.haloticket.booking_service.dto.response.TicketTypeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "ticket-service")
public interface TicketServiceClient {
    @GetMapping("/api/tickets/types/{id}")
    TicketTypeDto getTicketTypeById(@PathVariable("id") String id);
}
