package com.haloticket.event_service.service;

import com.haloticket.event_service.client.BookingInternalServiceClient;
import com.haloticket.event_service.client.BookingStatsResponse;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.EventViewLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EventAnalyticsService {

    private final EventRepository eventRepository;
    private final EventViewLogRepository viewLogRepository;
    private final BookingInternalServiceClient bookingClient;
    private final TicketServiceClient ticketClient;
    private final Clock clock;

    @Autowired
    public EventAnalyticsService(EventRepository eventRepository, EventViewLogRepository viewLogRepository,
                                 BookingInternalServiceClient bookingClient, TicketServiceClient ticketClient) {
        this(eventRepository, viewLogRepository, bookingClient, ticketClient, Clock.system(AnalyticsPeriod.ZONE));
    }

    EventAnalyticsService(EventRepository eventRepository, EventViewLogRepository viewLogRepository,
                          BookingInternalServiceClient bookingClient, TicketServiceClient ticketClient, Clock clock) {
        this.eventRepository = eventRepository;
        this.viewLogRepository = viewLogRepository;
        this.bookingClient = bookingClient;
        this.ticketClient = ticketClient;
        this.clock = clock;
    }

    public EventAnalyticsResponse getEventAnalytics(String eventId, String timeRange) {
        return getEventAnalytics(eventId, timeRange, null, null, false);
    }

    /**
     * from and to (whole days, to included) take the place of timeRange. compare adds the period of the same length that ends
     * where this one starts. A source that cannot be read sets partial and is named in unavailableSources; its figures are then
     * 0 and must not be shown as real zeros.
     */
    public EventAnalyticsResponse getEventAnalytics(String eventId, String timeRange, LocalDate from, LocalDate to, boolean compare) {
        eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        AnalyticsPeriod period = AnalyticsPeriod.resolve(timeRange, from, to, LocalDateTime.now(clock));
        List<String> unavailable = new ArrayList<>();

        Views views = views(eventId, period, unavailable);
        BookingStatsResponse allTime = bookingStats(eventId, null, unavailable);
        BookingStatsResponse inPeriod = bookingStats(eventId, period, unavailable);

        long buyers = inPeriod == null ? 0 : inPeriod.getBuyersCount();
        double conversion = views.unique > 0 ? Math.min(100.0, buyers * 100.0 / views.unique) : 0.0;

        List<EventAnalyticsResponse.DailyStat> daily = inPeriod == null ? List.of() : fillDays(period, inPeriod);
        List<EventAnalyticsResponse.DailyMetric> growth = daily.stream().map(d -> EventAnalyticsResponse.DailyMetric.builder()
                .label(d.getDate()).revPercent(d.getRevenue()).ticketsPercent(d.getTicketsSold()).build()).toList();

        long[] targets = targets(eventId, unavailable);

        EventAnalyticsResponse.PreviousPeriod previous = null;
        if (compare) previous = previous(eventId, period, views, inPeriod, unavailable);

        return EventAnalyticsResponse.builder()
                .pageViews(views.total)
                .uniqueUsers(views.unique)
                .buyers(buyers)
                .totalTicketsTarget(targets[0])
                .totalRevenueTarget(targets[1])
                .currentTicketsSold(allTime == null ? 0 : allTime.getTotalTicketsSold())
                .currentRevenue(allTime == null ? 0 : allTime.getTotalRevenue())
                .conversionRate(Math.round(conversion * 100.0) / 100.0)
                .periodFrom(period.firstDay().toString())
                .periodTo(period.lastDay().toString())
                .periodRevenue(inPeriod == null ? 0 : inPeriod.getTotalRevenue())
                .periodTicketsSold(inPeriod == null ? 0 : inPeriod.getTotalTicketsSold())
                .previous(previous)
                .dailyStats(daily)
                .growthMetrics(growth)
                .partial(!unavailable.isEmpty())
                .unavailableSources(unavailable)
                .build();
    }

    private record Views(long total, long unique) {}

    private Views views(String eventId, AnalyticsPeriod period, List<String> unavailable) {
        try {
            return new Views(viewLogRepository.countViewsBetween(eventId, period.start(), period.end()),
                    viewLogRepository.countUniqueViewsBetween(eventId, period.start(), period.end()));
        } catch (Exception e) {
            log.warn("Analytics of event {}: view counts unavailable: {}", eventId, e.toString());
            addOnce(unavailable, "views");
            return new Views(0, 0);
        }
    }

    /** period null means the whole life of the event. Null result: booking-service could not answer. */
    private BookingStatsResponse bookingStats(String eventId, AnalyticsPeriod period, List<String> unavailable) {
        try {
            BookingStatsResponse stats = period == null
                    ? bookingClient.getEventBookingStats(eventId)
                    : bookingClient.getEventBookingStatsBetween(eventId, iso(period.start()), iso(period.end()));
            if (stats == null) throw new IllegalStateException("empty answer");
            return stats;
        } catch (Exception e) {
            log.warn("Analytics of event {}: booking figures unavailable: {}", eventId, e.toString());
            addOnce(unavailable, "booking");
            return null;
        }
    }

    private EventAnalyticsResponse.PreviousPeriod previous(String eventId, AnalyticsPeriod period, Views now,
                                                          BookingStatsResponse nowBooking, List<String> unavailable) {
        AnalyticsPeriod before = period.previous();
        Views views = views(eventId, before, unavailable);
        BookingStatsResponse booking = bookingStats(eventId, before, unavailable);
        long revenue = booking == null ? 0 : booking.getTotalRevenue();
        long tickets = booking == null ? 0 : booking.getTotalTicketsSold();
        long buyers = booking == null ? 0 : booking.getBuyersCount();
        boolean comparable = booking != null && nowBooking != null;
        return EventAnalyticsResponse.PreviousPeriod.builder()
                .from(before.firstDay().toString()).to(before.lastDay().toString())
                .pageViews(views.total).uniqueUsers(views.unique).buyers(buyers).revenue(revenue).ticketsSold(tickets)
                .pageViewsChange(change(now.total, views.total))
                .buyersChange(comparable ? change(nowBooking.getBuyersCount(), buyers) : null)
                .revenueChange(comparable ? change(nowBooking.getTotalRevenue(), revenue) : null)
                .ticketsSoldChange(comparable ? change(nowBooking.getTotalTicketsSold(), tickets) : null)
                .build();
    }

    /** Percentage change against the previous value, one decimal; null when the previous value is 0 (no base to compare with). */
    static Double change(long current, long previous) {
        if (previous == 0) return null;
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    /** Every day of the period, with the days that had no sales as zero. */
    private static List<EventAnalyticsResponse.DailyStat> fillDays(AnalyticsPeriod period, BookingStatsResponse stats) {
        Map<String, BookingStatsResponse.DailyStat> byDate = new HashMap<>();
        if (stats.getDailyStats() != null) {
            for (BookingStatsResponse.DailyStat day : stats.getDailyStats()) byDate.put(day.getDate(), day);
        }
        List<EventAnalyticsResponse.DailyStat> days = new ArrayList<>();
        for (LocalDate day = period.firstDay(); !day.isAfter(period.lastDay()); day = day.plusDays(1)) {
            BookingStatsResponse.DailyStat sold = byDate.get(day.toString());
            days.add(EventAnalyticsResponse.DailyStat.builder().date(day.toString())
                    .revenue(sold == null ? 0 : sold.getRevenue()).ticketsSold(sold == null ? 0 : sold.getTicketsSold()).build());
        }
        return days;
    }

    /**
     * {tickets, revenue} the ticket types could sell in all. Ticket types of an event with showtimes are already one row per
     * showtime, so each row is counted once (no multiplying by the number of showtimes).
     */
    private long[] targets(String eventId, List<String> unavailable) {
        long tickets = 0;
        double revenue = 0;
        try {
            Object body = ticketClient.getTicketTypesByEventId(eventId);
            if (body instanceof List<?> types) {
                for (Object type : types) {
                    if (!(type instanceof Map<?, ?> map)) continue;
                    long quantity = Math.max(number(map.get("initialQuantity")), 0);
                    if (quantity == 0) quantity = Math.max(number(map.get("totalQuantity")), 0);
                    tickets += quantity;
                    revenue += quantity * (double) Math.max(number(map.get("price")), 0);
                }
            }
        } catch (Exception e) {
            log.warn("Analytics of event {}: ticket types unavailable: {}", eventId, e.toString());
            addOnce(unavailable, "tickets");
            return new long[]{0, 0};
        }
        return new long[]{tickets, Math.round(revenue)};
    }

    private static long number(Object value) {
        return value instanceof Number n ? Math.round(n.doubleValue()) : 0;
    }

    private static String iso(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void addOnce(List<String> list, String name) {
        if (!list.contains(name)) list.add(name);
    }
}
