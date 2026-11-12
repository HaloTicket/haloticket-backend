package com.haloticket.payment_service.dto.response;

import java.util.List;

public record BookingFinanceFacts(String eventId, List<String> paidBookingIds, long paidBookingAmount) {}
