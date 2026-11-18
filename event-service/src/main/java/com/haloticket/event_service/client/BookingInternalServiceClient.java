package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

/** Feign client gọi API nội bộ của booking-service để lấy thống kê đặt vé và dữ kiện báo cáo. */
@FeignClient(name = "booking-service")
public interface BookingInternalServiceClient {

    /** Lấy thống kê đặt vé của sự kiện trên toàn bộ vòng đời. */
    @GetMapping("/api/internal/bookings/events/{eventId}/stats")
    BookingStatsResponse getEventBookingStats(@PathVariable("eventId") String eventId);

    /** Cùng các số liệu nhưng chỉ trong khoảng [from, to); from và to là ISO local date-time (to không bao gồm). */
    @GetMapping("/api/internal/bookings/events/{eventId}/stats")
    BookingStatsResponse getEventBookingStatsBetween(@PathVariable("eventId") String eventId,
            @RequestParam("from") String from, @RequestParam("to") String to);

    /** Lấy dữ kiện báo cáo (sự kiện, số vé, trạng thái) cho danh sách mã đơn, gửi kèm header Authorization. */
    @PostMapping("/api/internal/bookings/report-facts")
    List<ReportBookingFact> getReportFacts(@RequestHeader("Authorization") String authorization,
            @RequestBody List<String> bookingIds);
}
