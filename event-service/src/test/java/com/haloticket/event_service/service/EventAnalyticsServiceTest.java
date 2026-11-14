package com.haloticket.event_service.service;

import com.haloticket.event_service.client.BookingInternalServiceClient;
import com.haloticket.event_service.client.BookingStatsResponse;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.EventViewLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventAnalyticsServiceTest {
    @Mock EventRepository events;
    @Mock EventViewLogRepository viewLogs;
    @Mock BookingInternalServiceClient booking;
    @Mock TicketServiceClient tickets;

    // 2026-11-14T17:30:00Z is 00:30 on 15 November in Ho Chi Minh City
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T17:30:00Z"), AnalyticsPeriod.ZONE);
    private EventAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new EventAnalyticsService(events, viewLogs, booking, tickets, clock);
        lenient().when(events.findById("e1")).thenReturn(Optional.of(new Event()));
    }

    private static BookingStatsResponse stats(long buyers, long tickets, long revenue, BookingStatsResponse.DailyStat... days) {
        BookingStatsResponse s = new BookingStatsResponse();
        s.setBuyersCount(buyers);
        s.setTotalTicketsSold(tickets);
        s.setTotalRevenue(revenue);
        s.setDailyStats(List.of(days));
        return s;
    }

    private static BookingStatsResponse.DailyStat day(String date, long tickets, long revenue) {
        BookingStatsResponse.DailyStat d = new BookingStatsResponse.DailyStat();
        d.setDate(date);
        d.setTicketsSold(tickets);
        d.setRevenue(revenue);
        return d;
    }

    @Test
    void aGivenPeriodFiltersEveryFigureAndFillsTheEmptyDays() {
        when(viewLogs.countViewsBetween(eq("e1"), any(), any())).thenReturn(40L);
        when(viewLogs.countUniqueViewsBetween(eq("e1"), any(), any())).thenReturn(20L);
        when(booking.getEventBookingStats("e1")).thenReturn(stats(9, 30, 3_000_000));
        when(booking.getEventBookingStatsBetween("e1", "2026-11-10T00:00", "2026-11-13T00:00"))
                .thenReturn(stats(4, 7, 700_000, day("2026-11-10", 5, 500_000), day("2026-11-12", 2, 200_000)));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "30d", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), false);

        verify(viewLogs).countViewsBetween("e1", LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 13, 0, 0));
        assertThat(r.getPeriodFrom()).isEqualTo("2026-11-10");
        assertThat(r.getPeriodTo()).isEqualTo("2026-11-12");
        assertThat(r.getPageViews()).isEqualTo(40);
        assertThat(r.getBuyers()).isEqualTo(4);
        assertThat(r.getPeriodRevenue()).isEqualTo(700_000);
        assertThat(r.getPeriodTicketsSold()).isEqualTo(7);
        // the all-time figures stay what the targets are compared with
        assertThat(r.getCurrentRevenue()).isEqualTo(3_000_000);
        assertThat(r.getCurrentTicketsSold()).isEqualTo(30);
        assertThat(r.getDailyStats()).extracting(EventAnalyticsResponse.DailyStat::getDate)
                .containsExactly("2026-11-10", "2026-11-11", "2026-11-12");
        assertThat(r.getDailyStats()).extracting(EventAnalyticsResponse.DailyStat::getRevenue).containsExactly(500_000L, 0L, 200_000L);
        assertThat(r.getConversionRate()).isEqualTo(20.0);
        assertThat(r.isPartial()).isFalse();
        assertThat(r.getPrevious()).isNull();
    }

    @Test
    void theComparisonPeriodIsTheSameLengthRightBeforeAndChangesArePercentages() {
        when(viewLogs.countViewsBetween(eq("e1"), any(), any())).thenReturn(10L, 5L);
        when(viewLogs.countUniqueViewsBetween(eq("e1"), any(), any())).thenReturn(10L, 5L);
        when(booking.getEventBookingStats("e1")).thenReturn(stats(0, 0, 0));
        when(booking.getEventBookingStatsBetween("e1", "2026-11-10T00:00", "2026-11-13T00:00")).thenReturn(stats(6, 12, 1_500_000));
        when(booking.getEventBookingStatsBetween("e1", "2026-11-07T00:00", "2026-11-10T00:00")).thenReturn(stats(4, 10, 1_000_000));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "30d", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), true);

        EventAnalyticsResponse.PreviousPeriod p = r.getPrevious();
        assertThat(p.getFrom()).isEqualTo("2026-11-07");
        assertThat(p.getTo()).isEqualTo("2026-11-09");
        assertThat(p.getRevenue()).isEqualTo(1_000_000);
        assertThat(p.getRevenueChange()).isEqualTo(50.0);
        assertThat(p.getTicketsSoldChange()).isEqualTo(20.0);
        assertThat(p.getBuyersChange()).isEqualTo(50.0);
        assertThat(p.getPageViewsChange()).isEqualTo(100.0);
    }

    @Test
    void aChangeAgainstNothingIsNullNotInfinite() {
        assertThat(EventAnalyticsService.change(5, 0)).isNull();
        assertThat(EventAnalyticsService.change(0, 4)).isEqualTo(-100.0);
        assertThat(EventAnalyticsService.change(1, 3)).isEqualTo(-66.7);
    }

    @Test
    void whenBookingServiceIsDownTheAnswerIsPartialAndHasNoMadeUpZeroDays() {
        when(viewLogs.countViewsBetween(eq("e1"), any(), any())).thenReturn(8L);
        when(viewLogs.countUniqueViewsBetween(eq("e1"), any(), any())).thenReturn(4L);
        when(booking.getEventBookingStats("e1")).thenThrow(new RuntimeException("booking down"));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenThrow(new RuntimeException("booking down"));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "7d", null, null, true);

        assertThat(r.isPartial()).isTrue();
        assertThat(r.getUnavailableSources()).containsExactly("booking");
        assertThat(r.getDailyStats()).isEmpty();
        assertThat(r.getPageViews()).isEqualTo(8);
        assertThat(r.getPrevious().getRevenueChange()).isNull();
    }

    @Test
    void aTicketServiceOutageIsReportedToo() {
        when(booking.getEventBookingStats("e1")).thenReturn(stats(0, 0, 0));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenReturn(stats(0, 0, 0));
        when(tickets.getTicketTypesByEventId("e1")).thenThrow(new RuntimeException("tickets down"));

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "30d", null, null, false);

        assertThat(r.isPartial()).isTrue();
        assertThat(r.getUnavailableSources()).containsExactly("tickets");
    }

    @Test
    void theRollingWindowsFollowTheHoChiMinhClockNotUtc() {
        when(booking.getEventBookingStats("e1")).thenReturn(stats(0, 0, 0));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenReturn(stats(0, 0, 0));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "24h", null, null, false);

        // now is 00:30 on the 15th there (it is still the 14th in UTC)
        verify(booking).getEventBookingStatsBetween("e1", "2026-11-14T00:30", "2026-11-15T00:30");
        assertThat(r.getPeriodFrom()).isEqualTo("2026-11-14");
        assertThat(r.getPeriodTo()).isEqualTo("2026-11-15");
        assertThat(r.getDailyStats()).hasSize(2);
    }

    @Test
    void ticketTypesAlreadyOnePerShowtimeAreCountedOnceEach() {
        when(booking.getEventBookingStats("e1")).thenReturn(stats(0, 0, 0));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenReturn(stats(0, 0, 0));
        // two showtimes, one row each with different stock: 100 x 200,000 and 50 x 300,000, plus a free type
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of(
                Map.of("showtimeId", "s1", "initialQuantity", 100, "price", 200000.0),
                Map.of("showtimeId", "s2", "initialQuantity", 50, "price", 300000.0),
                Map.of("showtimeId", "s2", "totalQuantity", 20, "isFree", true)));

        EventAnalyticsResponse r = service.getEventAnalytics("e1", "30d", null, null, false);

        assertThat(r.getTotalTicketsTarget()).isEqualTo(170);
        assertThat(r.getTotalRevenueTarget()).isEqualTo(35_000_000L);
    }

    @Test
    void conversionIsCappedAndNeverDividesByZero() {
        when(viewLogs.countUniqueViewsBetween(eq("e1"), any(), any())).thenReturn(0L);
        when(booking.getEventBookingStats("e1")).thenReturn(stats(3, 3, 3));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenReturn(stats(3, 3, 3));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());
        assertThat(service.getEventAnalytics("e1", "30d", null, null, false).getConversionRate()).isEqualTo(0.0);

        when(viewLogs.countUniqueViewsBetween(eq("e1"), any(), any())).thenReturn(2L);
        assertThat(service.getEventAnalytics("e1", "30d", null, null, false).getConversionRate()).isEqualTo(100.0);
    }

    @Test
    void badPeriodsAreRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> service.getEventAnalytics("e1", "30d", LocalDate.of(2026, 11, 1), null, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> service.getEventAnalytics("e1", "30d", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> service.getEventAnalytics("e1", "30d", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 1), false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("at most");
        verify(booking, never()).getEventBookingStats(anyString());
    }

    @Test
    void anUnknownEventIsNotFound() {
        when(events.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getEventAnalytics("nope", "30d"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void theOldTwoArgumentCallStillWorksLikeBefore() {
        when(booking.getEventBookingStats("e1")).thenReturn(stats(1, 2, 3));
        when(booking.getEventBookingStatsBetween(eq("e1"), anyString(), anyString())).thenReturn(stats(1, 2, 3));
        when(tickets.getTicketTypesByEventId("e1")).thenReturn(List.of());
        EventAnalyticsResponse r = service.getEventAnalytics("e1", "30d");
        assertThat(r.getPrevious()).isNull();
        assertThat(r.getDailyStats()).hasSize(31);
    }
}
