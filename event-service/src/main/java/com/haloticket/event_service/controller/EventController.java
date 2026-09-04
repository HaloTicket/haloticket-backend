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

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@RequestBody EventCreateRequest request) {
        return new ResponseEntity<>(eventService.createEvent(request), HttpStatus.CREATED);
    }

    @GetMapping("/organizer-profile")
    public ResponseEntity<OrganizerDto> getOrganizerProfile(@RequestParam(required = false) String userId) {
        OrganizerDto dto = eventService.getOrganizerProfile(userId);
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
