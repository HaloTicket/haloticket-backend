package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/internal/bookings")
@RequiredArgsConstructor
public class BookingInternalController {

    private final BookingRepository bookingRepository;

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

    public record BookingReportFact(String bookingId, String eventId, long tickets, String status) {}

    @GetMapping("/events/{eventId}/stats")
    public ResponseEntity<BookingStatsResponse> getEventBookingStats(@PathVariable String eventId) {
        BookingStatsResponse response = new BookingStatsResponse();
        response.setBuyersCount(bookingRepository.countUniqueBuyersByEventId(eventId));
        response.setTotalRevenue(bookingRepository.sumRevenueByEventId(eventId));
        response.setTotalTicketsSold(bookingRepository.sumTicketsSoldByEventId(eventId));
        
        response.setDailyStats(new ArrayList<>());
        
        return ResponseEntity.ok(response);
    }

    @Data
    public static class BookingStatsResponse {
        private long buyersCount;
        private long totalTicketsSold;
        private double totalRevenue;
        private java.util.List<DailyStat> dailyStats;

        @Data
        public static class DailyStat {
            private String date;
            private long ticketsSold;
            private double revenue;
        }
    }
}
