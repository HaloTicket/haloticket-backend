package com.haloticket.booking_service.service;

import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingStatsServiceTest {

    private static BookingRepository.PaidOrderRow row(String customer, LocalDateTime paidAt, Double amount, Long tickets) {
        return new BookingRepository.PaidOrderRow() {
            public String getCustomerId() { return customer; }
            public LocalDateTime getPaidAt() { return paidAt; }
            public Double getAmount() { return amount; }
            public Long getTickets() { return tickets; }
        };
    }

    @Test
    void sumsRevenueAndTicketsAndCountsEachBuyerOnce() {
        BookingStatsService.Stats s = BookingStatsService.summarise(List.of(
                row("u1", LocalDateTime.of(2026, 11, 10, 9, 0), 100_000.0, 2L),
                row("u1", LocalDateTime.of(2026, 11, 10, 20, 0), 50_000.4, 1L),
                row("u2", LocalDateTime.of(2026, 11, 12, 23, 59), 200_000.0, 4L)));

        assertThat(s.buyers()).isEqualTo(2);
        assertThat(s.ticketsSold()).isEqualTo(7);
        assertThat(s.revenue()).isEqualTo(350_000L);
    }

    @Test
    void groupsByTheDayTheOrderWasPaidAndSortsTheDays() {
        BookingStatsService.Stats s = BookingStatsService.summarise(List.of(
                row("u2", LocalDateTime.of(2026, 11, 12, 23, 59), 200_000.0, 4L),
                row("u1", LocalDateTime.of(2026, 11, 10, 0, 0), 100_000.0, 2L),
                row("u3", LocalDateTime.of(2026, 11, 10, 23, 59), 20_000.0, 1L)));

        assertThat(s.daily()).containsExactly(
                new BookingStatsService.DailySales(LocalDate.of(2026, 11, 10), 3, 120_000),
                new BookingStatsService.DailySales(LocalDate.of(2026, 11, 12), 4, 200_000));
    }

    @Test
    void missingAmountsAndCountsAreZeroNotErrors() {
        BookingStatsService.Stats s = BookingStatsService.summarise(List.of(row("u1", LocalDateTime.of(2026, 11, 10, 9, 0), null, null)));
        assertThat(s.revenue()).isZero();
        assertThat(s.ticketsSold()).isZero();
        assertThat(s.buyers()).isEqualTo(1);
    }

    @Test
    void onlyPaidAndConfirmedOrdersCountAndAnEmptyWindowSkipsTheQuery() {
        BookingRepository repo = mock(BookingRepository.class);
        when(repo.findPaidOrderRows(anyString(), any(), any(), any())).thenReturn(List.of());
        BookingStatsService service = new BookingStatsService(repo);
        LocalDateTime from = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 8, 0, 0);

        service.statsFor("e1", from, to);
        verify(repo).findPaidOrderRows("e1", List.of(BookingStatus.PAID, BookingStatus.CONFIRMED), from, to);

        BookingStatsService.Stats none = service.statsFor("e1", to, from);
        assertThat(none.revenue()).isZero();
        assertThat(none.daily()).isEmpty();
        verify(repo, never()).findPaidOrderRows("e1", List.of(BookingStatus.PAID, BookingStatus.CONFIRMED), to, from);
    }

    @Test
    void noBoundsMeansTheWholeLifeOfTheEvent() {
        BookingRepository repo = mock(BookingRepository.class);
        when(repo.findPaidOrderRows(anyString(), any(), any(), any())).thenReturn(List.of(
                row("u1", LocalDateTime.of(2026, 1, 1, 0, 0), 10.0, 1L)));
        BookingStatsService.Stats s = new BookingStatsService(repo).statsFor("e1", null, null);
        assertThat(s.ticketsSold()).isEqualTo(1);
    }
}
