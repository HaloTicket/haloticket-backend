package com.haloticket.event_service.client;

/** Dữ kiện đặt vé do booking-service trả về cho báo cáo: số vé và trạng thái của từng đơn đặt. */
public record ReportBookingFact(String bookingId, String eventId, long tickets, String status) {}
