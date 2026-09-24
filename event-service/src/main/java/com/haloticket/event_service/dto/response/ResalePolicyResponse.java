package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.entity.ResalePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long version) {
    public static ResalePolicyResponse from(ResalePolicy policy) { return new ResalePolicyResponse(policy.getEventId(), policy.isEnabled(), policy.getMinPricePercent(), policy.getMaxPricePercent(), policy.getOpensAt(), policy.getClosesAt(), policy.getTicketTypeScope(), policy.getAllowedTicketTypeIds(), policy.getTermsVersion(), policy.getVersion()); }
}
