package com.haloticket.event_service.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Một khoản thanh toán đã xác nhận do payment-service trả về cho báo cáo, gồm doanh thu và thời điểm xác nhận. */
public record ReportPayment(String bookingId, String paymentId, Instant confirmedAt, BigDecimal revenue) {}
