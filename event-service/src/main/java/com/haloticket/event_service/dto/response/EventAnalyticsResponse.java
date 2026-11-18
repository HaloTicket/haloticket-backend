package com.haloticket.event_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Số liệu phân tích của một sự kiện. Tiền tính bằng đồng (VND), số nguyên. {@code pageViews}, {@code uniqueUsers}, {@code buyers}, {@code periodRevenue},
 * {@code periodTicketsSold} và {@code conversionRate} tính theo kỳ được yêu cầu; {@code currentTicketsSold} và {@code currentRevenue} tính cho cả vòng đời sự kiện
 * (đó là số dùng để so với các mục tiêu bán hàng).
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

    /** Kỳ mà các số liệu theo kỳ bao phủ: ngày theo múi giờ của nền tảng, {@code periodTo} là ngày cuối cùng được tính. */
    private String periodFrom;
    private String periodTo;
    private long periodRevenue;
    private long periodTicketsSold;

    /** Kỳ có cùng độ dài, kết thúc ngay tại điểm kỳ hiện tại bắt đầu; {@code null} khi không yêu cầu so sánh. */
    private PreviousPeriod previous;

    /** Doanh thu và số vé theo từng ngày trong kỳ, ngày nào cũng có mặt (bằng 0 khi không bán được gì). */
    private List<DailyStat> dailyStats;

    /** {@code true} khi có nguồn dữ liệu không đọc được; {@code unavailableSources} liệt kê tên các nguồn đó (booking, tickets, views). */
    private boolean partial;
    private List<String> unavailableSources;

    /** Giữ lại cho client cũ: cùng các ngày như {@code dailyStats}, nhưng doanh thu và số vé là số thuần (không phải phần trăm). */
    private List<DailyMetric> growthMetrics;

    /** Một điểm của chuỗi {@code growthMetrics} dành cho client cũ: nhãn ngày, doanh thu và số vé. */
    @Data
    @Builder
    public static class DailyMetric {
        private String label;
        private double revPercent;
        private double ticketsPercent;
    }

    /** Doanh thu và số vé bán được trong một ngày. */
    @Data
    @Builder
    public static class DailyStat {
        private String date;
        private long revenue;
        private long ticketsSold;
    }

    /** Các trường {@code change*} là phần trăm thay đổi so với kỳ trước, {@code null} khi giá trị kỳ trước bằng 0. Lớp này chứa số liệu của kỳ trước. */
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
