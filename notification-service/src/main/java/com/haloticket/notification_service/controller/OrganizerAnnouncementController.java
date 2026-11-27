package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.dto.request.OrganizerAnnouncementRequest.*;
import com.haloticket.notification_service.dto.response.OrganizerAnnouncementResponse;
import com.haloticket.notification_service.service.OrganizerAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class OrganizerAnnouncementController {
    private final OrganizerAnnouncementService announcements;
    private final NotificationScopeGuard scopes;

    @GetMapping("/events/{eventId}/announcements")
    public OrganizerAnnouncementResponse.Page list(@PathVariable String eventId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return announcements.list(eventId, authorization, page, size);
    }

    @PostMapping("/events/{eventId}/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizerAnnouncementResponse submit(@PathVariable String eventId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Submit request) {
        return announcements.submit(eventId, authorization, request);
    }

    @PutMapping("/events/{eventId}/announcements/{id}")
    public OrganizerAnnouncementResponse resubmit(@PathVariable String eventId, @PathVariable String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Resubmit request) {
        return announcements.resubmit(eventId, id, authorization, request);
    }

    @PostMapping("/events/{eventId}/announcements/recipient-count")
    public RecipientCount recipientCount(@PathVariable String eventId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Preview request) {
        return announcements.recipientCount(eventId, authorization, request.target());
    }

    @GetMapping("/admin/moderation")
    public OrganizerAnnouncementResponse.Page pending(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String q) {
        scopes.requireAdmin();
        return announcements.pending(q, page, size);
    }

    @PatchMapping("/admin/moderation/{id}")
    public OrganizerAnnouncementResponse review(@PathVariable String id, @RequestBody Review request) {
        return announcements.review(id, scopes.requireAdmin(), request);
    }
}
