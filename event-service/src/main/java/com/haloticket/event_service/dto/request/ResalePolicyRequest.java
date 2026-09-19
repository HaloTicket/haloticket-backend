package com.haloticket.event_service.dto.request;

import java.math.BigDecimal;
import java.time.Instant;

public record ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion) {}
