package com.haloticket.marketplace_service.dto;

import java.math.BigDecimal;

public record ResaleEventSummary(String eventId, long activeListings, BigDecimal minPrice) {}
