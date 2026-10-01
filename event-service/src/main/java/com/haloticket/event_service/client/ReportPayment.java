package com.haloticket.event_service.client;

import java.math.BigDecimal;
import java.time.Instant;

public record ReportPayment(String bookingId, String paymentId, Instant confirmedAt, BigDecimal revenue) {}
