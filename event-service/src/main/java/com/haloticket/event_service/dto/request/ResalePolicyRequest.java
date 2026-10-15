package com.haloticket.event_service.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long expectedVersion, BigDecimal serviceFeePercent, Boolean requireApproval) {
    public ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
            Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
            String termsVersion, Long expectedVersion) {
        this(enabled, minPricePercent, maxPricePercent, opensAt, closesAt, ticketTypeScope,
                allowedTicketTypeIds, termsVersion, expectedVersion, null, null);
    }
}
