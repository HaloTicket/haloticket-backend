package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "ticket-service", contextId = "announcementTicketOwners",
        path = "/api/internal/tickets/events")
public interface TicketOwnerClient {
    @GetMapping("/{eventId}/notification-owners")
    List<Ownership> owners(@RequestHeader(AnnouncementDirectoryClient.INTERNAL_TOKEN) String token,
            @PathVariable String eventId, @RequestParam String actorId, @RequestParam String ticketTypeId,
            @RequestParam String showtimeId, @RequestParam String after, @RequestParam int limit);

    record Ownership(String ownerId, boolean valid, boolean checkedIn) {}
}
