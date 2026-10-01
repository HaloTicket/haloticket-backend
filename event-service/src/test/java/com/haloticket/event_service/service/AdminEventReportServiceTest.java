package com.haloticket.event_service.service;

import com.haloticket.event_service.client.*;
import com.haloticket.event_service.dto.response.AdminEventReportResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminEventReportServiceTest {
    @Mock EventRepository events;
    @Mock PaymentReportClient payments;
    @Mock BookingInternalServiceClient bookings;
    @InjectMocks AdminEventReportService service;

    @Test
    void combinesCurrentAndPreviousPeriodsDeduplicatesFactsAndFillsDateHoles() {
        LocalDate from = LocalDate.parse("2026-09-02");
        LocalDate to = LocalDate.parse("2026-09-03");
        Event event = Event.builder().id("event-1").title("Show").eventStatus(EventStatus.LIVE).organizers(List.of()).build();
        when(events.findAllForAdminReport()).thenReturn(List.of(event));
        when(payments.getReportPayments(eq("Bearer admin"), any(), any())).thenReturn(new ReportPaymentsResponse(List.of(
                payment("current", "2026-09-02T01:00:00Z", "25.50"),
                payment("refunded", "2026-09-02T02:00:00Z", "20.00"),
                payment("previous", "2026-08-31T17:30:00Z", "10.00"),
                payment("unmatched", "2026-09-03T01:00:00Z", "99.00")), 0));
        when(bookings.getReportFacts(eq("Bearer admin"), anyList())).thenReturn(List.of(
                new ReportBookingFact("current", "event-1", 2, "PAID"),
                new ReportBookingFact("current", "event-1", 2, "PAID"),
                new ReportBookingFact("refunded", "event-1", 2, "REFUNDED"),
                new ReportBookingFact("previous", "event-1", 1, "PAID")));

        AdminEventReportResponse result = service.report("Bearer admin", from, to, null, null, null, null,
                "revenue", 1, 10, null, false);

        assertThat(result.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(result.summary().revenue()).isEqualByComparingTo("45.50");
        assertThat(result.summary().orders()).isEqualTo(2);
        assertThat(result.summary().tickets()).isEqualTo(4);
        assertThat(result.previous().revenue()).isEqualByComparingTo("10.00");
        assertThat(result.timeline()).extracting(AdminEventReportResponse.TimelinePoint::orders).containsExactly(2L, 0L);
        assertThat(result.completeness()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).hasSize(2);
        verify(payments).getReportPayments(eq("Bearer admin"),
                eq(Instant.parse("2026-08-30T17:00:00Z")), eq(Instant.parse("2026-09-03T17:00:00Z")));
    }

    @Test
    void paginationDoesNotChangeScopeSummary() {
        List<Event> source = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> Event.builder().id(String.format("event-%02d", i)).title("Show " + i).eventStatus(EventStatus.LIVE).organizers(List.of()).build()).toList();
        when(events.findAllForAdminReport()).thenReturn(source);
        when(payments.getReportPayments(anyString(), any(), any())).thenReturn(new ReportPaymentsResponse(List.of(), 0));

        var result = service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"), null,
                null, null, null, "title", 999_999, 10, null, false);

        assertThat(result.totalElements()).isEqualTo(12);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.summary().orders()).isZero();
    }

    @Test
    void excludesInvalidMetricsAndFlagsBookingsThatAreNotPaid() {
        Event event = Event.builder().id("event-1").title("Show").eventStatus(EventStatus.LIVE).organizers(List.of()).build();
        when(events.findAllForAdminReport()).thenReturn(List.of(event));
        when(payments.getReportPayments(anyString(), any(), any())).thenReturn(new ReportPaymentsResponse(List.of(
                payment("bad-tickets", "2026-09-01T01:00:00Z", "20.00"),
                payment("not-paid", "2026-09-01T02:00:00Z", "30.00")), 0));
        when(bookings.getReportFacts(eq("Bearer admin"), anyList())).thenReturn(List.of(
                new ReportBookingFact("bad-tickets", "event-1", -1, "PAID"),
                new ReportBookingFact("not-paid", "event-1", 1, "PENDING")));

        var result = service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"), null,
                null, null, null, "revenue", 1, 10, null, false);

        assertThat(result.summary().revenue()).isEqualByComparingTo("0");
        assertThat(result.summary().orders()).isZero();
        assertThat(result.completeness()).isEqualTo("PARTIAL");
        assertThat(result.warnings()).hasSize(2);
    }

    @Test
    void restrictsSummaryTimelineRowsExportDetailAndOptionsToPublishedLifecycle() {
        Event approved = event("approved", "Approved", EventStatus.APPROVED, EventApprovalStatus.APPROVED, "cat-approved");
        Event updatePending = event("update-pending", "Update pending", EventStatus.APPROVED, EventApprovalStatus.UPDATE_PENDING, "cat-update");
        Event live = event("live", "Live", EventStatus.LIVE, EventApprovalStatus.APPROVED, "cat-live");
        Event completed = event("completed", "Completed", EventStatus.COMPLETED, EventApprovalStatus.APPROVED, "cat-completed");
        Event draft = event("draft", "Old draft", EventStatus.DRAFT, EventApprovalStatus.PENDING, "cat-draft");
        draft.setEndTime(LocalDateTime.parse("2020-01-01T00:00:00"));
        Event pending = event("pending", "Old pending", EventStatus.PENDING, EventApprovalStatus.PENDING, "cat-pending");
        pending.setEndTime(LocalDateTime.parse("2020-01-01T00:00:00"));
        Event cancelled = event("cancelled", "Cancelled", EventStatus.CANCELLED, EventApprovalStatus.APPROVED, "cat-cancelled");
        when(events.findAllForAdminReport()).thenReturn(List.of(approved, updatePending, live, completed, draft, pending, cancelled));
        when(payments.getReportPayments(eq("Bearer admin"), any(), any())).thenReturn(new ReportPaymentsResponse(List.of(
                payment("approved-current", "2026-09-01T01:00:00Z", "10.00"),
                payment("update-current", "2026-09-02T01:00:00Z", "5.00"),
                payment("draft-current", "2026-09-01T02:00:00Z", "100.00"),
                payment("pending-current", "2026-09-02T02:00:00Z", "200.00"),
                payment("completed-previous", "2026-08-31T16:00:00Z", "20.00"),
                payment("pending-previous", "2026-08-31T15:00:00Z", "400.00")), 0));
        when(bookings.getReportFacts(eq("Bearer admin"), anyList())).thenReturn(List.of(
                new ReportBookingFact("approved-current", "approved", 2, "PAID"),
                new ReportBookingFact("update-current", "update-pending", 1, "PAID"),
                new ReportBookingFact("draft-current", "draft", 8, "PAID"),
                new ReportBookingFact("pending-current", "pending", 9, "PAID"),
                new ReportBookingFact("completed-previous", "completed", 1, "REFUNDED"),
                new ReportBookingFact("pending-previous", "pending", 10, "PAID")));

        var report = service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                null, null, null, null, "revenue", 1, 10, null, false);
        var export = service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                null, null, null, null, "revenue", 1, 10, null, true);
        var detail = service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                null, null, null, null, "revenue", 1, 10, "completed", false);

        assertThat(report.summary().revenue()).isEqualByComparingTo("15.00");
        assertThat(report.summary().orders()).isEqualTo(2);
        assertThat(report.summary().tickets()).isEqualTo(3);
        assertThat(report.previous().revenue()).isEqualByComparingTo("20.00");
        assertThat(report.previous().orders()).isEqualTo(1);
        assertThat(report.timeline()).extracting(AdminEventReportResponse.TimelinePoint::orders).containsExactly(1L, 1L);
        assertThat(report.totalElements()).isEqualTo(4);
        assertThat(report.rows()).extracting(AdminEventReportResponse.EventRow::eventId)
                .containsExactly("approved", "update-pending", "completed", "live");
        assertThat(report.completeness()).isEqualTo("COMPLETE");
        assertThat(export.rows()).extracting(AdminEventReportResponse.EventRow::eventId)
                .containsExactly("approved", "update-pending", "completed", "live");
        assertThat(export.summary()).isEqualTo(report.summary());
        assertThat(detail.rows()).extracting(AdminEventReportResponse.EventRow::eventId).containsExactly("completed");
        assertThat(detail.summary().orders()).isZero();
        assertThat(detail.previous().orders()).isEqualTo(1);
        assertThat(report.options().statuses()).containsExactly("APPROVED", "COMPLETED", "LIVE");
        assertThat(report.options().categories()).extracting(AdminEventReportResponse.Option::id)
                .containsExactlyInAnyOrder("cat-approved", "cat-update", "cat-live", "cat-completed");
        assertThat(report.options().categories()).extracting(AdminEventReportResponse.Option::id)
                .doesNotContain("cat-draft", "cat-pending", "cat-cancelled");

        assertThatThrownBy(() -> service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                null, null, null, null, "revenue", 1, 10, "draft", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        assertThatThrownBy(() -> service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                null, null, null, null, "revenue", 1, 10, "pending", true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void invalidLongRangesAndPaymentFailuresAreNotReportedAsZero() {
        assertThatThrownBy(() -> service.report("Bearer admin", LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-02"),
                null, null, null, null, "revenue", 1, 10, null, false)).isInstanceOf(ResponseStatusException.class);

        when(payments.getReportPayments(anyString(), any(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.report("Bearer admin", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"),
                null, null, null, null, "revenue", 1, 10, null, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
        verify(events, never()).findAllForAdminReport();
    }

    private static ReportPayment payment(String booking, String confirmedAt, String revenue) {
        return new ReportPayment(booking, "payment-" + booking, Instant.parse(confirmedAt), new BigDecimal(revenue));
    }

    private static Event event(String id, String title, EventStatus status, EventApprovalStatus approvalStatus, String categoryId) {
        String organizerId = "organizer-" + id;
        EventOrganizer organizer = EventOrganizer.builder().id(organizerId).name("Organizer " + id).build();
        EventCategory category = EventCategory.builder().id(categoryId).name(categoryId).build();
        return Event.builder().id(id).title(title).eventStatus(status).eventApprovalStatus(approvalStatus)
                .category(category).organizers(List.of(organizer)).build();
    }
}
