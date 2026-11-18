package com.haloticket.payment_service.dto.response;

import java.util.List;

/** Số liệu tài chính từ booking-service cho một sự kiện: danh sách đơn đã thanh toán và tổng tiền các đơn đó. */
public record BookingFinanceFacts(String eventId, List<String> paidBookingIds, long paidBookingAmount) {}
