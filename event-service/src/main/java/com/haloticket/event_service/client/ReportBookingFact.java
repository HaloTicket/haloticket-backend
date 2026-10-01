package com.haloticket.event_service.client;

public record ReportBookingFact(String bookingId, String eventId, long tickets, String status) {}
