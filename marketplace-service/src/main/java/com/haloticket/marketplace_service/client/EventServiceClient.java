package com.haloticket.marketplace_service.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
@FeignClient(name="event-service", path="/api/events")
public interface EventServiceClient {
    @GetMapping("/{eventId}/resale-policy")
    ResalePolicyResponse getResalePolicy(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    @GetMapping("/{eventId}/members/my-role")
    String getMyRole(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    record ResalePolicyResponse(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version) {}
}
