package com.haloticket.event_service.client;

import lombok.Data;

import java.util.List;

/** Thống kê đặt vé của một sự kiện do booking-service trả về: số người mua, số vé bán, doanh thu và số liệu theo ngày. */
@Data
public class BookingStatsResponse {
    private long buyersCount;
    private long totalTicketsSold;
    private long totalRevenue;
    
    private List<DailyStat> dailyStats;

    /** Số vé bán và doanh thu của một ngày. */
    @Data
    public static class DailyStat {
        private String date;
        private long ticketsSold;
        private long revenue;
    }
}
