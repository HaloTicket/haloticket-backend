package com.haloticket.marketplace_service.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
@FeignClient(name="event-service", path="/api/events")
public interface EventServiceClient {
    @GetMapping("/{eventId}/resale-policy")
    ResalePolicyResponse getResalePolicy(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    @GetMapping("/{eventId}/gift-policy")
    GiftPolicyResponse getGiftPolicy(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    @GetMapping("/{eventId}/members/my-access")
    com.haloticket.marketplace_service.authz.EventAccessView getMyAccess(@PathVariable("eventId") String eventId, @RequestHeader("Authorization") String authorization);
    @GetMapping("/{eventId}/members/my-role")
    String getMyRole(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    record ResalePolicyResponse(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version,
                                BigDecimal serviceFeePercent, boolean requireApproval, Instant opensAt, String ticketTypeScope, java.util.Set<String> allowedTicketTypeIds) {
        public ResalePolicyResponse(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version) {
            this(enabled, minPricePercent, maxPricePercent, closesAt, termsVersion, version, new BigDecimal("5"), false, null, "ALL", java.util.Set.of());
        }
    }
    record GiftPolicyResponse(boolean enabled, Instant opensAt, Instant closesAt, int invitationTtlHours, boolean requireEkyc,
                              int maxGiftCount, String ticketTypeScope, java.util.Set<String> allowedTicketTypeIds, Long version) {}
}
