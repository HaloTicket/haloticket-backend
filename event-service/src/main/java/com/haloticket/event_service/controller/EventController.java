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
import org.springframework.security.core.Authentication;

import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import com.haloticket.event_service.service.EventAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final EventAccessService eventAccessService;

    @PostMapping("/test-questions")
    public ResponseEntity<String> testQuestions(@RequestBody EventCreateRequest request) {
        try {
            return ResponseEntity.ok(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getCustomQuestions()));
        } catch (Exception e) {
            return ResponseEntity.ok("Error: " + e.getMessage());
        }
    }

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

    /** Re-sends the stored ticket snapshot to ticket-service. Same permission as editing the event (EVENT_EDIT; ROLE_ADMIN passes). */
    @PostMapping("/{id}/tickets/resync")
    public ResponseEntity<EventResponse> resyncTickets(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization
    ) {
        eventAccessService.require(id, Permission.EVENT_EDIT, authorization);
        return ResponseEntity.ok(eventService.resyncTickets(id));
    }

    @ExceptionHandler(TicketSyncRejectedException.class)
    public ResponseEntity<Map<String, Object>> ticketSyncRejected(TicketSyncRejectedException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.getCode());
        body.put("eventId", exception.getEventId());
        body.put("message", exception.getCode());
        body.putAll(exception.getDetails()); // ticketTypeId, name, sold, held, minAllowed
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(TicketSyncFailedException.class)
    public ResponseEntity<Map<String, Object>> ticketSyncFailed(TicketSyncFailedException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TICKETS_SYNC_FAILED");
        body.put("eventId", exception.getEventId());
        body.put("message", "Event saved but its ticket types could not be synchronized. Try again with resync.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
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

    @GetMapping("/test-auth")
    public ResponseEntity<String> testAuth(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) return ResponseEntity.status(401).body("JWT is NULL");
        return ResponseEntity.ok("JWT is VALID: " + jwt.getSubject());
    }

    @GetMapping("/my-events")
    public ResponseEntity<List<EventResponse>> getMyEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        System.out.println("DEBUG: getMyEvents called. auth=" + auth);
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<EventResponse> res = eventService.getMyEvents(authorization);
        System.out.println("DEBUG: getMyEvents returned " + (res != null ? res.size() : "null") + " events for user " + jwt.getSubject());
        return ResponseEntity.ok(res);
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

    @PatchMapping("/{id}/revert-approval")
    public ResponseEntity<EventResponse> revertApproval(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Hoàn tác phê duyệt nhầm bởi Quản trị viên";
        return ResponseEntity.ok(eventService.revertApproval(id, reason));
    }

    @PatchMapping("/{id}/cancel-approval-request")
    public ResponseEntity<EventResponse> cancelApprovalRequest(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(eventService.cancelApprovalRequest(id, authorization));
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
