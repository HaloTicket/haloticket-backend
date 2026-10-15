package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.entity.ResalePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long version, BigDecimal serviceFeePercent, boolean requireApproval) {
    public ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
            Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
            String termsVersion, Long version) {
        this(eventId, enabled, minPricePercent, maxPricePercent, opensAt, closesAt, ticketTypeScope,
                allowedTicketTypeIds, termsVersion, version, new BigDecimal("5"), false);
    }

    public static ResalePolicyResponse from(ResalePolicy policy) {
        return new ResalePolicyResponse(policy.getEventId(), policy.isEnabled(), policy.getMinPricePercent(),
                policy.getMaxPricePercent(), policy.getOpensAt(), policy.getClosesAt(), policy.getTicketTypeScope(),
                policy.getAllowedTicketTypeIds(), policy.getTermsVersion(), policy.getVersion(),
                policy.getServiceFeePercent(), policy.isRequireApproval());
    }
}
