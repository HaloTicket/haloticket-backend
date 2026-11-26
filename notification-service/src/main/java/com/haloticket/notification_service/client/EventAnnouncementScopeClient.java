package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "event-service", contextId = "eventAnnouncementScopeClient",
        path = "/api/internal/notification-announcement-scope")
public interface EventAnnouncementScopeClient {
    @GetMapping("/{eventId}")
    EventScope scope(@PathVariable String eventId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    record EventScope(String eventId, String eventTitle, String userId, String role) {}
}
