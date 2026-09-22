package com.haloticket.event_service.client;

import lombok.Data;

import java.util.List;

@Data
public class BookingStatsResponse {
    private long buyersCount;
    private long totalTicketsSold;
    private double totalRevenue;
    
    private List<DailyStat> dailyStats;

    @Data
    public static class DailyStat {
        private String date;
        private long ticketsSold;
        private double revenue;
    }
}
