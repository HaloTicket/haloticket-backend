package com.haloticket.event_service.controller;

import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.service.EventAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/notification-announcement-scope")
public class NotificationAnnouncementScopeController {
    private final EventAccessService access;
    private final EventRepository events;

    @GetMapping("/{eventId}")
    public AnnouncementScope scope(@PathVariable String eventId,
                                   @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        var caller = access.requireAnnouncementAccess(eventId, authorization);
        var event = events.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        return new AnnouncementScope(event.getId(), event.getTitle(), caller.userId(), caller.role());
    }

    public record AnnouncementScope(String eventId, String eventTitle, String userId, StaffRole role) {}
}
