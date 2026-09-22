package com.haloticket.event_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class EventAnalyticsResponse {
    private long pageViews;
    private long uniqueUsers;
    private long buyers;
    private long totalTicketsTarget;
    private long currentTicketsSold;
    private double currentRevenue;
    private double totalRevenueTarget;
    private double conversionRate;
    
    private List<DailyMetric> growthMetrics;

    @Data
    @Builder
    public static class DailyMetric {
        private String label;
        private double revPercent;
        private double ticketsPercent;
    }
}
