package com.haloticket.event_service.service;

import com.haloticket.event_service.client.BookingInternalServiceClient;
import com.haloticket.event_service.client.BookingStatsResponse;
import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.EventViewLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventAnalyticsService {

    private final EventRepository eventRepository;
    private final EventViewLogRepository viewLogRepository;
    private final BookingInternalServiceClient bookingClient;
    private final com.haloticket.event_service.client.TicketServiceClient ticketClient;

    public EventAnalyticsResponse getEventAnalytics(String eventId, String timeRange) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));

        LocalDateTime since = LocalDateTime.now();
        if ("24h".equals(timeRange)) {
            since = since.minusHours(24);
        } else {
            since = since.minusDays(30);
        }

        long pageViews = viewLogRepository.countViewsSince(eventId, since);
        long uniqueUsers = viewLogRepository.countUniqueViewsSince(eventId, since);

        // fetch booking stats
        BookingStatsResponse bookingStats;
        try {
            bookingStats = bookingClient.getEventBookingStats(eventId);
        } catch (Exception e) {
            // fallback if booking service is down or no bookings
            bookingStats = new BookingStatsResponse();
            bookingStats.setBuyersCount(0);
            bookingStats.setTotalRevenue(0.0);
            bookingStats.setTotalTicketsSold(0);
            bookingStats.setDailyStats(new ArrayList<>());
        }

        long buyers = bookingStats.getBuyersCount();
        long currentTicketsSold = bookingStats.getTotalTicketsSold();
        double currentRevenue = bookingStats.getTotalRevenue();

        double conversionRate = uniqueUsers > 0 ? ((double) buyers / uniqueUsers) * 100 : 0.0;

        long totalTicketsTarget = 0;
        double totalRevenueTarget = 0.0;
        
        try {
            int showtimesCount = 1;
            if (event.getShowtimes() != null && !event.getShowtimes().isEmpty()) {
                showtimesCount = event.getShowtimes().size();
            } else if (event.getStartTime() != null && event.getEndTime() != null) {
                showtimesCount = (int) ChronoUnit.DAYS.between(
                        event.getStartTime().toLocalDate(),
                        event.getEndTime().toLocalDate()
                ) + 1;
            }
            Object typesObj = ticketClient.getTicketTypesByEventId(eventId);
            if (typesObj instanceof List) {
                List<?> types = (List<?>) typesObj;
                for (Object t : types) {
                    if (t instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) t;
                        Number initialQty = (Number) map.get("initialQuantity");
                        Number totalQty = (Number) map.get("totalQuantity");
                        long quantity = (initialQty != null) ? initialQty.longValue() : ((totalQty != null) ? totalQty.longValue() : 0L);
                        
                        Double price = null;
                        if (map.get("price") instanceof Number) {
                            price = ((Number) map.get("price")).doubleValue();
                        }
                        
                        if (quantity > 0) {
                            long totalQuantityForType = quantity * showtimesCount;
                            totalTicketsTarget += totalQuantityForType;
                            if (price != null) {
                                totalRevenueTarget += (totalQuantityForType * price);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<EventAnalyticsResponse.DailyMetric> growthMetrics = new ArrayList<>();
        if (bookingStats.getDailyStats() != null) {
            for (BookingStatsResponse.DailyStat stat : bookingStats.getDailyStats()) {
                growthMetrics.add(EventAnalyticsResponse.DailyMetric.builder()
                        .label(stat.getDate())
                        .revPercent(stat.getRevenue()) // frontend will calculate percentage from these values
                        .ticketsPercent(stat.getTicketsSold())
                        .build());
            }
        }

        return EventAnalyticsResponse.builder()
                .pageViews(pageViews)
                .uniqueUsers(uniqueUsers)
                .buyers(buyers)
                .totalTicketsTarget(totalTicketsTarget) // will update if we fetch from ticket-service
                .currentTicketsSold(currentTicketsSold)
                .currentRevenue(currentRevenue)
                .totalRevenueTarget(totalRevenueTarget)
                .conversionRate(Math.round(conversionRate * 100.0) / 100.0)
                .growthMetrics(growthMetrics)
                .build();
    }
}
