package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/internal/bookings")
@RequiredArgsConstructor
public class BookingInternalController {

    private final BookingRepository bookingRepository;

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
