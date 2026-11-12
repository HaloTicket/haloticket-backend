package com.haloticket.payment_service.client;

import com.haloticket.payment_service.dto.response.EventFinanceSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.List;

@FeignClient(name = "event-service", path = "/api/events")
public interface EventServiceClient {
    @GetMapping("/my-events")
    List<EventFinanceSource> getMyEvents(@RequestHeader("Authorization") String authorization);
    @GetMapping
    List<EventFinanceSource> getAllEvents();
}
