package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;
import com.haloticket.event_service.dto.response.VenueSeatmapsResponse;
import com.haloticket.event_service.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public ResponseEntity<List<VenueResponse>> getAllVenues() {
        return ResponseEntity.ok(venueService.getAllVenues());
    }

    @GetMapping("/{id}")
    public ResponseEntity<VenueResponse> getVenueById(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.getVenueById(id));
    }

    @PostMapping
    public ResponseEntity<VenueResponse> createVenue(@RequestBody VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.createVenue(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VenueResponse> updateVenue(
            @PathVariable Long id,
            @RequestBody VenueRequest request) {
        return ResponseEntity.ok(venueService.updateVenue(id, request));
    }

    @PatchMapping("/{id}/verify")
    public ResponseEntity<VenueResponse> toggleVerification(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.toggleVerification(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVenue(@PathVariable Long id) {
        venueService.deleteVenue(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/seatmaps")
    public ResponseEntity<VenueSeatmapsResponse> getVenueSeatmaps(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.getVenueSeatmaps(id));
    }

    @PutMapping("/{id}/seatmap-template")
    public ResponseEntity<VenueResponse> saveVenueSeatmapTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String template = body.get("seatmapTemplate");
        return ResponseEntity.ok(venueService.saveVenueSeatmapTemplate(id, template));
    }
}
