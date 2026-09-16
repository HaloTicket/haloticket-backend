package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @RequestHeader("Authorization") String authorization,
            @RequestBody EventCreateRequest request
    ) {
        return new ResponseEntity<>(eventService.createEvent(request, authorization), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> updateEvent(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization,
            @RequestBody EventCreateRequest request
    ) {
        return ResponseEntity.ok(eventService.updateEvent(id, request, authorization));
    }

    @GetMapping("/organizer-profile")
    public ResponseEntity<OrganizerDto> getOrganizerProfile(
            @RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal Jwt jwt
    ) {
        OrganizerDto dto = eventService.getOrganizerProfile(authorization);
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<EventCategoryResponse>> getAllCategories() {
        return ResponseEntity.ok(eventService.getAllCategories());
    }

    @GetMapping("/approvals")
    public ResponseEntity<List<EventResponse>> getApprovalEvents(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(eventService.getApprovalEvents(status));
    }

    @PatchMapping("/{id}/approve")
    public ResponseEntity<EventResponse> approveEvent(@PathVariable String id) {
        return ResponseEntity.ok(eventService.approveEvent(id));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<EventResponse> rejectEvent(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Không đạt tiêu chuẩn kiểm duyệt";
        String notes = body != null ? body.get("notes") : "";
        return ResponseEntity.ok(eventService.rejectEvent(id, reason, notes));
    }

    @GetMapping("/trending-searches")
    public ResponseEntity<List<String>> getTrendingSearches(@RequestParam(defaultValue = "3") int limit) {
        return ResponseEntity.ok(eventService.getTopSearchedKeywords(limit));
    }

    @RequestMapping(value = "/search-log", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Void> logSearchQuery(@RequestParam String query) {
        eventService.logSearchQuery(query);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEventById(@PathVariable String id) {
        EventResponse response = eventService.getEventById(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
