package com.haloticket.marketplace_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

@FeignClient(name = "ticket-service", path = "/api/tickets")
public interface TicketServiceClient {
    @GetMapping("/{ticketId}/resale-eligibility")
    ResaleEligibilityResponse getResaleEligibility(@RequestHeader("Authorization") String authorization, @PathVariable String ticketId);

    record ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                     BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                     Long policyVersion, Instant closesAt, String termsVersion) {}
}
