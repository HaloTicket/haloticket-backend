package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

@FeignClient(name = "event-service", path = "/api/internal/notification-teams")
public interface EventTeamClient {
    @GetMapping("/{eventId}")
    List<String> teamIds(@RequestHeader(AnnouncementDirectoryClient.INTERNAL_TOKEN) String internalToken,
                         @PathVariable String eventId, @RequestParam Instant cutoff,
                         @RequestParam(required = false) String after, @RequestParam int limit);

    @GetMapping("/{eventId}/staff")
    List<String> announcementStaff(@RequestHeader(AnnouncementDirectoryClient.INTERNAL_TOKEN) String token,
            @PathVariable String eventId, @RequestParam String actorId, @RequestParam Instant cutoff,
            @RequestParam(required = false) String role, @RequestParam String memberId,
            @RequestParam String after, @RequestParam int limit);

    @GetMapping("/{eventId}/access")
    void requireAnnouncementAccess(@RequestHeader(AnnouncementDirectoryClient.INTERNAL_TOKEN) String token,
            @PathVariable String eventId, @RequestParam String actorId);
}
