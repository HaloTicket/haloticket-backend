package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookingInternalReportFactsTest {
    @Test
    void returnsBatchTicketTotalsAndBookingStatus() {
        BookingRepository repository = mock(BookingRepository.class);
        BookingRepository.ReportFact row = new BookingRepository.ReportFact() {
            public String getBookingId() { return "booking-1"; }
            public String getEventId() { return "event-1"; }
            public Long getTickets() { return 3L; }
            public BookingStatus getStatus() { return BookingStatus.PAID; }
        };
        when(repository.findReportFactsByBookingIds(List.of("booking-1"))).thenReturn(List.of(row));
        var controller = new BookingInternalController(repository);

        var response = controller.getReportFacts(java.util.Arrays.asList("booking-1", "booking-1", null));

        assertThat(response.getBody()).containsExactly(new BookingInternalController.BookingReportFact("booking-1", "event-1", 3, "PAID"));
        verify(repository).findReportFactsByBookingIds(List.of("booking-1"));
    }

    @Test
    void emptyIdsNeverQueryAllBookings() {
        BookingRepository repository = mock(BookingRepository.class);
        var controller = new BookingInternalController(repository);

        assertThat(controller.getReportFacts(List.of()).getBody()).isEmpty();
        verifyNoInteractions(repository);
    }
}
