package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.dto.request.AnnouncementRequest;
import com.haloticket.notification_service.dto.response.AnnouncementResponse;
import com.haloticket.notification_service.service.AdminAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {
    private final NotificationScopeGuard scopes;
    private final AdminAnnouncementService announcements;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnnouncementResponse create(@RequestBody AnnouncementRequest request) {
        return announcements.enqueue(scopes.requireAdmin(), request);
    }

    @GetMapping("/{id}")
    public AnnouncementResponse get(@PathVariable String id) {
        scopes.requireAdmin();
        return announcements.get(id);
    }
}
