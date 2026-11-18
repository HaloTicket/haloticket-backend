package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.service.BookingStatsService;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * API nội bộ giữa các service (không mở cho người dùng cuối): dữ liệu báo cáo và thống kê đặt vé của sự kiện.
 */
@RestController
@RequestMapping("/api/internal/bookings")
@RequiredArgsConstructor
public class BookingInternalController {

    private final BookingRepository bookingRepository;
    private final BookingStatsService statsService;

    /**
     * Trả dữ kiện báo cáo (sự kiện, số vé, trạng thái) cho danh sách mã đơn, đọc theo từng lô 5000 mã.
     * @param bookingIds danh sách mã đơn, tối đa 100000; phần tử null và trùng bị bỏ
     * @return danh sách dữ kiện, rỗng nếu không có mã hợp lệ
     * @throws ResponseStatusException HTTP 400 khi vượt quá 100000 mã
     */
    @PostMapping("/report-facts")
    public ResponseEntity<List<BookingReportFact>> getReportFacts(@RequestBody List<String> bookingIds) {
        if (bookingIds == null || bookingIds.size() > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at most 100000 booking IDs");
        }
        List<String> ids = bookingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return ResponseEntity.ok(List.of());
        if (ids.size() > 100_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at most 100000 booking IDs");
        List<BookingReportFact> facts = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 5_000) {
            List<String> chunk = ids.subList(start, Math.min(start + 5_000, ids.size()));
            bookingRepository.findReportFactsByBookingIds(chunk).stream()
                    .map(row -> new BookingReportFact(row.getBookingId(), row.getEventId(), row.getTickets() == null ? 0 : row.getTickets(),
                            row.getStatus() == null ? null : row.getStatus().name()))
                    .forEach(facts::add);
        }
        return ResponseEntity.ok(facts);
    }

    /** Dữ kiện báo cáo của một đơn: mã đơn, sự kiện, tổng số vé và trạng thái. */
    public record BookingReportFact(String bookingId, String eventId, long tickets, String status) {}

    /** from và to (ISO local date-time, to không bao gồm) là tùy chọn: bỏ trống thì số liệu tính trên toàn bộ vòng đời sự kiện. */
    @GetMapping("/events/{eventId}/stats")
    public ResponseEntity<BookingStatsResponse> getEventBookingStats(
            @PathVariable String eventId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        BookingStatsService.Stats stats = statsService.statsFor(eventId, from, to);
        BookingStatsResponse response = new BookingStatsResponse();
        response.setBuyersCount(stats.buyers());
        response.setTotalTicketsSold(stats.ticketsSold());
        response.setTotalRevenue(stats.revenue());
        response.setDailyStats(stats.daily().stream().map(d -> {
            BookingStatsResponse.DailyStat day = new BookingStatsResponse.DailyStat();
            day.setDate(d.date().toString());
            day.setTicketsSold(d.ticketsSold());
            day.setRevenue(d.revenue());
            return day;
        }).toList());
        return ResponseEntity.ok(response);
    }

    /** Kết quả thống kê đặt vé của một sự kiện: số người mua, số vé bán, doanh thu và số liệu theo ngày. */
    @Data
    public static class BookingStatsResponse {
        private long buyersCount;
        private long totalTicketsSold;
        private long totalRevenue;
        private java.util.List<DailyStat> dailyStats;

        /** Số liệu một ngày: số vé bán và doanh thu. */
        @Data
        public static class DailyStat {
            private String date;
            private long ticketsSold;
            private long revenue;
        }
    }
}
