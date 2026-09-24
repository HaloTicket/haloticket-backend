package com.haloticket.event_service.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long expectedVersion) {}
