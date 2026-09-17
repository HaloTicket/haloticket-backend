package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.entity.ResalePolicy;
import java.math.BigDecimal;
import java.time.Instant;

public record ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version) {
    public static ResalePolicyResponse from(ResalePolicy policy) { return new ResalePolicyResponse(policy.getEventId(), policy.isEnabled(), policy.getMinPricePercent(), policy.getMaxPricePercent(), policy.getClosesAt(), policy.getTermsVersion(), policy.getVersion()); }
}
