package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.config.ReconciliationProperties;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {

    private PaymentExceptionRepository exceptions;
    private BookingRepository bookings;
    private TicketStockClient tickets;
    private ReconciliationProperties properties;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        exceptions = mock(PaymentExceptionRepository.class);
        bookings = mock(BookingRepository.class);
        tickets = mock(TicketStockClient.class);
        GaReservationProperties token = new GaReservationProperties();
        token.setInternalToken("tok");
        properties = new ReconciliationProperties();
        service = new ReconciliationService(exceptions, bookings, tickets, token, properties);
    }

    private static BookingRepository.PaidCandidate candidate(String id, long expected, int paidMinutesAgo) {
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(paidMinutesAgo);
        return new BookingRepository.PaidCandidate() {
            public String getBookingId() { return id; }
            public String getBookingCode() { return "HALO-" + id; }
            public LocalDateTime getPaidAt() { return paidAt; }
            public Long getExpectedTickets() { return expected; }
        };
    }

    private void issued(Map<String, Integer> counts) {
        when(tickets.issuedCounts(eq("tok"), any())).thenReturn(Map.of("counts", counts));
    }

    // --- paid without tickets --------------------------------------------------------

    @Test
    void onlyOrdersWithFewerTicketsThanExpectedAreReported() {
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of(
                candidate("full", 2, 30), candidate("none", 3, 45), candidate("partial", 4, 20), candidate("zero-expected", 0, 20)));
        issued(Map.of("full", 2, "partial", 1)); // "none" is missing from the answer: 0

        var result = service.paidWithoutTickets();

        assertThat(result.items()).extracting(ReconciliationService.PaidWithoutTicketsItem::bookingId)
                .containsExactly("none", "partial");
        assertThat(result.total()).isEqualTo(2);
        var none = result.items().get(0);
        assertThat(none.expectedTickets()).isEqualTo(3);
        assertThat(none.issuedTickets()).isZero();
        assertThat(none.bookingCode()).isEqualTo("HALO-none");
        assertThat(none.minutesSincePaid()).isBetween(44L, 46L);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void graceWindowIsPassedToTheQuery() {
        properties.setPaidGraceMinutes(15);
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of());

        service.paidWithoutTickets();

        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bookings).findPaidCandidates(any(), to.capture(), any());
        assertThat(to.getValue()).isBetween(LocalDateTime.now().minusMinutes(16), LocalDateTime.now().minusMinutes(14));
        verify(tickets, never()).issuedCounts(anyString(), any());
    }

    @Test
    void askingTicketServiceInBatchesOfAtMost200() {
        properties.setBatchSize(1000);
        properties.setMaxBatches(2);
        List<BookingRepository.PaidCandidate> full = new ArrayList<>();
        for (int i = 0; i < 200; i++) full.add(candidate("b" + i, 1, 30));
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(full);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 200; i++) counts.put("b" + i, 1);
        issued(counts);

        var result = service.paidWithoutTickets();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(bookings, times(2)).findPaidCandidates(any(), any(), page.capture());
        assertThat(page.getAllValues()).allSatisfy(p -> assertThat(p.getPageSize()).isEqualTo(200));
        assertThat(result.items()).isEmpty();
        assertThat(result.truncated()).isTrue(); // more candidates than maxBatches allows
    }

    @Test
    void ticketServiceErrorIsA502NotAnEmptyList() {
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of(candidate("a", 1, 30)));
        when(tickets.issuedCounts(anyString(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.paidWithoutTickets()).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getReason()).isEqualTo("RECONCILIATION_SOURCE_UNAVAILABLE");
        });
    }

    @Test
    void answerWithoutCountsIsAFailureNotZeroTickets() {
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of(candidate("a", 1, 30)));
        when(tickets.issuedCounts(anyString(), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.paidWithoutTickets()).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // --- stock ---------------------------------------------------------------------------

    @Test
    void stockDiscrepanciesPassThroughTheLimit() {
        when(tickets.stockReconciliation(eq("tok"), anyInt())).thenReturn(List.of(Map.of("ticketTypeId", "t1", "diffStockVsIssued", 2)));

        var result = service.stockDiscrepancies();

        verify(tickets).stockReconciliation("tok", 200);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0)).containsEntry("ticketTypeId", "t1");
    }

    @Test
    void stockSourceFailureIsA502() {
        when(tickets.stockReconciliation(anyString(), anyInt())).thenThrow(new RuntimeException("503"));

        assertThatThrownBy(() -> service.stockDiscrepancies()).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("RECONCILIATION_SOURCE_UNAVAILABLE"));
    }

    @Test
    void blankInternalTokenIsASourceFailure() {
        GaReservationProperties blank = new GaReservationProperties();
        ReconciliationService noToken = new ReconciliationService(exceptions, bookings, tickets, blank, properties);

        assertThatThrownBy(noToken::stockDiscrepancies).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(tickets, never()).stockReconciliation(anyString(), anyInt());
    }

    // --- summary -----------------------------------------------------------------------

    @Test
    void summaryCountsThreeGroups() {
        when(exceptions.countByStatus(PaymentExceptionStatus.OPEN)).thenReturn(3L);
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of(candidate("a", 1, 30)));
        issued(Map.of());
        when(tickets.stockReconciliation(anyString(), anyInt())).thenReturn(List.of(Map.of("ticketTypeId", "t1"), Map.of("ticketTypeId", "t2")));

        Map<String, Object> summary = service.summary();

        assertThat(summary).containsEntry("openExceptions", 3L).containsEntry("paidWithoutTickets", 1).containsEntry("stockDiscrepancies", 2);
    }

    @Test
    void summaryGivesNullForASourceThatFailsNotZero() {
        when(exceptions.countByStatus(PaymentExceptionStatus.OPEN)).thenReturn(0L);
        when(bookings.findPaidCandidates(any(), any(), any())).thenReturn(List.of(candidate("a", 1, 30)));
        when(tickets.issuedCounts(anyString(), any())).thenThrow(new RuntimeException("down"));
        when(tickets.stockReconciliation(anyString(), anyInt())).thenReturn(List.of());

        Map<String, Object> summary = service.summary();

        assertThat(summary).containsEntry("openExceptions", 0L).containsEntry("paidWithoutTickets", null).containsEntry("stockDiscrepancies", 0);
    }

    // --- exceptions list and resolve -----------------------------------------------------

    private static PaymentException open(String id) {
        return PaymentException.builder().id(id).bookingId("b-1").paymentId("pay-1").kind(PaymentExceptionKind.LATE_PAYMENT_NO_STOCK)
                .amount(1000d).provider("VNPAY").status(PaymentExceptionStatus.OPEN).detail("d").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void listFiltersByStatusAndReturnsTheBookingCode() {
        Page<PaymentException> page = new PageImpl<>(List.of(open("e-1")), org.springframework.data.domain.PageRequest.of(0, 20), 1);
        when(exceptions.findByStatus(eq(PaymentExceptionStatus.OPEN), any(Pageable.class))).thenReturn(page);
        when(bookings.findAllById(any())).thenReturn(List.of(com.haloticket.booking_service.entity.Booking.builder().id("b-1").bookingCode("HALO-1").build()));

        var result = service.exceptions(PaymentExceptionStatus.OPEN, 0, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).bookingCode()).isEqualTo("HALO-1");
        assertThat(result.items().get(0).kind()).isEqualTo("LATE_PAYMENT_NO_STOCK");
        verify(exceptions, never()).findAll(any(Pageable.class));
    }

    @Test
    void resolveSetsStatusWhoAndWhenButNotTheBooking() {
        PaymentException exception = open("e-1");
        when(exceptions.findById("e-1")).thenReturn(Optional.of(exception));

        var item = service.resolve("e-1", "  refunded on the gateway  ", true, "admin-sub");

        assertThat(exception.getStatus()).isEqualTo(PaymentExceptionStatus.REFUNDED_MANUALLY);
        assertThat(exception.getResolvedBy()).isEqualTo("admin-sub");
        assertThat(exception.getResolvedAt()).isNotNull();
        assertThat(exception.getResolutionNote()).isEqualTo("refunded on the gateway");
        assertThat(item.status()).isEqualTo("REFUNDED_MANUALLY");
        verify(bookings, never()).save(any());

        PaymentException other = open("e-2");
        when(exceptions.findById("e-2")).thenReturn(Optional.of(other));
        service.resolve("e-2", "handled", false, "admin-sub");
        assertThat(other.getStatus()).isEqualTo(PaymentExceptionStatus.RESOLVED);
    }

    @Test
    void resolveRejectsBadNotesUnknownIdsAndRepeats() {
        assertThatThrownBy(() -> service.resolve("e-1", "ab", false, "a")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("INVALID_RESOLUTION_NOTE"));
        assertThatThrownBy(() -> service.resolve("e-1", null, false, "a")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.resolve("e-1", "x".repeat(501), false, "a")).isInstanceOf(ResponseStatusException.class);

        when(exceptions.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("missing", "valid note", false, "a")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        PaymentException done = open("e-3");
        done.setStatus(PaymentExceptionStatus.RESOLVED);
        when(exceptions.findById("e-3")).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.resolve("e-3", "valid note", false, "a")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(exceptions, never()).save(any());
    }

    // --- auto-resolve of issuance failures ---------------------------------------------

    private static PaymentException issuanceFailure(String id, String bookingId) {
        PaymentException e = new PaymentException();
        e.setId(id);
        e.setBookingId(bookingId);
        e.setKind(PaymentExceptionKind.ISSUANCE_FAILED);
        e.setStatus(PaymentExceptionStatus.OPEN);
        return e;
    }

    private static BookingRepository.ReportFact fact(String bookingId, long tickets) {
        return new BookingRepository.ReportFact() {
            public String getBookingId() { return bookingId; }
            public String getEventId() { return "ev"; }
            public Long getTickets() { return tickets; }
            public com.haloticket.booking_service.enums.BookingStatus getStatus() { return null; }
        };
    }

    @Test
    void anIssuanceFailureWhoseTicketsNowExistIsClosedBySystem() {
        PaymentException done = issuanceFailure("e-done", "b-done");
        PaymentException partial = issuanceFailure("e-partial", "b-partial");
        when(exceptions.findTop200ByKindAndStatus(PaymentExceptionKind.ISSUANCE_FAILED, PaymentExceptionStatus.OPEN))
                .thenReturn(List.of(done, partial));
        when(bookings.findReportFactsByBookingIds(any())).thenReturn(List.of(fact("b-done", 2), fact("b-partial", 3)));
        issued(Map.of("b-done", 2, "b-partial", 1));

        int closed = service.autoResolveIssued();

        assertThat(closed).isEqualTo(1);
        assertThat(done.getStatus()).isEqualTo(PaymentExceptionStatus.RESOLVED);
        assertThat(done.getResolvedBy()).isEqualTo("system");
        assertThat(done.getResolvedAt()).isNotNull();
        assertThat(partial.getStatus()).isEqualTo(PaymentExceptionStatus.OPEN);
        verify(exceptions).saveAll(List.of(done));
    }

    @Test
    void nothingIsClosedWhenTicketServiceCannotAnswer() {
        PaymentException open = issuanceFailure("e-1", "b-1");
        when(exceptions.findTop200ByKindAndStatus(any(), any())).thenReturn(List.of(open));
        when(bookings.findReportFactsByBookingIds(any())).thenReturn(List.of(fact("b-1", 1)));
        when(tickets.issuedCounts(any(), any())).thenThrow(new IllegalStateException("down"));

        assertThat(service.autoResolveIssued()).isZero();
        assertThat(open.getStatus()).isEqualTo(PaymentExceptionStatus.OPEN);
        verify(exceptions, never()).saveAll(any());
    }

    @Test
    void anOrderWithNoKnownTicketCountIsNeverAutoClosed() {
        PaymentException open = issuanceFailure("e-1", "gone");
        when(exceptions.findTop200ByKindAndStatus(any(), any())).thenReturn(List.of(open));
        when(bookings.findReportFactsByBookingIds(any())).thenReturn(List.of());
        issued(Map.of("gone", 5));

        assertThat(service.autoResolveIssued()).isZero();
        assertThat(open.getStatus()).isEqualTo(PaymentExceptionStatus.OPEN);
    }

    // --- stock fix ------------------------------------------------------------------------

    @Test
    void fixStockReturnsTheRepairedRow() {
        when(tickets.fixStock("tok", "tt-1")).thenReturn(Map.of("ticketTypeId", "tt-1", "remaining", 999));

        assertThat(service.fixStock("tt-1")).containsEntry("remaining", 999);
    }

    @Test
    void fixStockPassesOnRefusalsAndReportsAnOutageAs502() {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.POST, "http://ticket/x", Map.of(), null, null, null);
        when(tickets.fixStock("tok", "tt-409")).thenThrow(new feign.FeignException.Conflict("c", request, null, Map.of()));
        when(tickets.fixStock("tok", "tt-500")).thenThrow(new feign.FeignException.InternalServerError("x", request, null, Map.of()));

        assertThatThrownBy(() -> service.fixStock("tt-409")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.fixStock("tt-500")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
