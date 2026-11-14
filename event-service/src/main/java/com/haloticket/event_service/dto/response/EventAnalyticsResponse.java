package com.haloticket.event_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Figures of one event. Money is whole VND. pageViews, uniqueUsers, buyers, periodRevenue, periodTicketsSold and
 * conversionRate cover the requested period; currentTicketsSold and currentRevenue are for the whole life of the event
 * (they are what the sales targets are compared with).
 */
@Data
@Builder
public class EventAnalyticsResponse {
    private long pageViews;
    private long uniqueUsers;
    private long buyers;
    private long totalTicketsTarget;
    private long currentTicketsSold;
    private long currentRevenue;
    private long totalRevenueTarget;
    private double conversionRate;

    /** The period the period figures cover: dates in the platform zone, periodTo is the last included day. */
    private String periodFrom;
    private String periodTo;
    private long periodRevenue;
    private long periodTicketsSold;

    /** The period of the same length that ended where this one starts; null when the comparison was not asked for. */
    private PreviousPeriod previous;

    /** Revenue and tickets per day over the period, every day present (zero when nothing was sold). */
    private List<DailyStat> dailyStats;

    /** True when a source could not be read; unavailableSources names them (booking, tickets, views). */
    private boolean partial;
    private List<String> unavailableSources;

    /** Kept for older clients: the same days as dailyStats, revenue and tickets as plain numbers (not percentages). */
    private List<DailyMetric> growthMetrics;

    @Data
    @Builder
    public static class DailyMetric {
        private String label;
        private double revPercent;
        private double ticketsPercent;
    }

    @Data
    @Builder
    public static class DailyStat {
        private String date;
        private long revenue;
        private long ticketsSold;
    }

    /** change* are percentages against the previous period, null when the previous value is 0. */
    @Data
    @Builder
    public static class PreviousPeriod {
        private String from;
        private String to;
        private long pageViews;
        private long uniqueUsers;
        private long buyers;
        private long revenue;
        private long ticketsSold;
        private Double pageViewsChange;
        private Double buyersChange;
        private Double revenueChange;
        private Double ticketsSoldChange;
    }
}
