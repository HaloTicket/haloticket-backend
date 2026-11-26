package com.haloticket.ticket_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import com.haloticket.ticket_service.authz.EventAccessView;

import java.time.LocalDateTime;
import java.util.List;

/** Feign client gọi event-service để lấy chi tiết sự kiện và quyền của người dùng trên sự kiện. */
@FeignClient(name = "event-service", url = "${EVENT_SERVICE_URL:}")
public interface EventServiceClient {
    @GetMapping("/api/internal/notification-teams/{eventId}/access")
    void requireAnnouncementAccess(@RequestHeader("X-Internal-Token") String token, @PathVariable String eventId,
                                   @RequestParam String actorId, @RequestParam String showtimeId);


    /** Lấy chi tiết sự kiện, gồm thời gian và các suất diễn. */
    @GetMapping("/api/events/{id}")
    EventDetailsResponse getEventById(@PathVariable("id") String id);

    /** Lấy vai trò và quyền của người gọi (theo header {@code Authorization}) trên sự kiện. */
    @GetMapping("/api/events/{id}/members/my-access")
    EventAccessView getMyAccess(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    /** Chi tiết sự kiện: mã, thời gian bắt đầu, kết thúc và các suất diễn. */
    record EventDetailsResponse(
            String id,
            LocalDateTime startTime,
            LocalDateTime endTime,
            List<ShowtimeDetailsDto> showtimes
    ) {}

    /** Thông tin một suất diễn: mã, thời gian bắt đầu và kết thúc. */
    record ShowtimeDetailsDto(
            String id,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {}
}
