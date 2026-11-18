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

/** Thống kê hiệu quả của một sự kiện cho nhà tổ chức. Ghép lượt xem (cơ sở dữ liệu của event-service), số liệu đặt vé (booking-service) và số vé phát hành (ticket-service); nguồn nào lỗi thì đánh dấu kết quả là một phần thay vì làm hỏng toàn bộ. */
@Service
@Slf4j
public class EventAnalyticsService {

    private final EventRepository eventRepository;
    private final EventViewLogRepository viewLogRepository;
    private final BookingInternalServiceClient bookingClient;
    private final TicketServiceClient ticketClient;
    private final Clock clock;

    /** Constructor dùng khi chạy thật, lấy đồng hồ hệ thống theo múi giờ của nền tảng. */
    @Autowired
    public EventAnalyticsService(EventRepository eventRepository, EventViewLogRepository viewLogRepository,
                                 BookingInternalServiceClient bookingClient, TicketServiceClient ticketClient) {
        this(eventRepository, viewLogRepository, bookingClient, ticketClient, Clock.system(AnalyticsPeriod.ZONE));
    }

    /** Constructor cho phép tiêm đồng hồ, dùng để cố định thời điểm hiện tại khi kiểm thử. */
    EventAnalyticsService(EventRepository eventRepository, EventViewLogRepository viewLogRepository,
                          BookingInternalServiceClient bookingClient, TicketServiceClient ticketClient, Clock clock) {
        this.eventRepository = eventRepository;
        this.viewLogRepository = viewLogRepository;
        this.bookingClient = bookingClient;
        this.ticketClient = ticketClient;
        this.clock = clock;
    }

    /** Thống kê của sự kiện theo cửa sổ trượt {@code timeRange}, không so sánh kỳ trước. */
    public EventAnalyticsResponse getEventAnalytics(String eventId, String timeRange) {
        return getEventAnalytics(eventId, timeRange, null, null, false);
    }

    /**
     * Thống kê của sự kiện trong một khoảng thời gian: lượt xem, người xem riêng biệt, người mua, tỷ lệ chuyển đổi, doanh thu, vé bán, so với mục tiêu và theo từng ngày.
     * {@code from} và {@code to} (ngày trọn vẹn, gồm cả ngày {@code to}) thay cho {@code timeRange}. {@code compare} thêm số liệu của
     * khoảng cùng độ dài liền trước. Nguồn không đọc được sẽ đặt {@code partial} và được nêu trong {@code unavailableSources};
     * số liệu của nguồn đó khi ấy là 0 và không được hiển thị như số 0 thật.
     *
     * @param eventId id sự kiện
     * @param timeRange cửa sổ trượt {@code 24h}, {@code 7d} hoặc {@code 30d}
     * @param from ngày bắt đầu, phải đi cùng {@code to}
     * @param to ngày kết thúc
     * @param compare true để kèm số liệu kỳ trước
     * @return số liệu thống kê
     * @throws ResponseStatusException 404 khi không có sự kiện
     * @throws AnalyticsPeriod.InvalidPeriodException khi khoảng thời gian không hợp lệ
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

    /** Số lượt xem trong một khoảng: tổng lượt và số phiên khác nhau. */
    private record Views(long total, long unique) {}

    /** Đếm lượt xem và số phiên khác nhau trong khoảng; lỗi truy vấn thì trả 0 và ghi nhận nguồn {@code views} không khả dụng. */
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

    /** {@code period} null nghĩa là toàn bộ vòng đời của sự kiện. Kết quả null nghĩa là booking-service không trả lời được. */
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

    /** Tính số liệu của khoảng liền trước và phần trăm thay đổi so với hiện tại. Thay đổi về đơn hàng chỉ có khi cả hai kỳ đều đọc được booking-service. */
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

    /** Phần trăm thay đổi so với giá trị trước, làm tròn một chữ số thập phân; null khi giá trị trước bằng 0 (không có gốc để so sánh). */
    static Double change(long current, long previous) {
        if (previous == 0) return null;
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    /** Đủ mọi ngày trong khoảng, những ngày không bán được vé được điền bằng 0. */
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
     * Trả về {@code {số vé, doanh thu}} mà các loại vé có thể bán tối đa. Loại vé của sự kiện có suất diễn đã là một dòng cho mỗi
     * suất nên mỗi dòng chỉ tính một lần (không nhân với số suất). Không đọc được ticket-service thì trả {@code {0, 0}} và ghi nhận nguồn {@code tickets} không khả dụng.
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

    /** Làm tròn một giá trị số bất kỳ thành {@code long}; không phải số thì là 0. */
    private static long number(Object value) {
        return value instanceof Number n ? Math.round(n.doubleValue()) : 0;
    }

    /** Định dạng thời điểm theo ISO, cắt tới mili giây, để gửi cho booking-service. */
    private static String iso(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** Thêm tên nguồn dữ liệu vào danh sách không khả dụng nếu chưa có. */
    private static void addOnce(List<String> list, String name) {
        if (!list.contains(name)) list.add(name);
    }
}
