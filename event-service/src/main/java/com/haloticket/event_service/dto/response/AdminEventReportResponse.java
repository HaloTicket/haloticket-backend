package com.haloticket.event_service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminEventReportResponse(LocalDate from, LocalDate to, String timezone, Instant generatedAt,
        String completeness, List<String> warnings, Summary summary, Summary previous,
        List<TimelinePoint> timeline, List<EventRow> rows, int page, int size, long totalElements,
        int totalPages, Options options) {
    public record Summary(BigDecimal revenue, long orders, long tickets, long eventsWithSales) {}
    public record TimelinePoint(LocalDate date, BigDecimal revenue, long orders, long tickets) {}
    public record EventRow(String eventId, String title, String organizer, String category, String status,
            BigDecimal revenue, long orders, long tickets) {}
    public record Option(String id, String name) {}
    public record Options(List<Option> categories, List<Option> organizers, List<String> statuses) {}
}
