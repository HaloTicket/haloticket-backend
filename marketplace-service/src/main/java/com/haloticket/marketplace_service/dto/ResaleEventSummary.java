package com.haloticket.marketplace_service.dto;

import java.math.BigDecimal;

/** Tóm tắt một sự kiện có vé bán lại: số tin đang mở và giá thấp nhất. */
public record ResaleEventSummary(String eventId, long activeListings, BigDecimal minPrice) {}
