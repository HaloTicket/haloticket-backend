package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping("/types")
    public ResponseEntity<TicketTypeResponse> createTicketType(@RequestBody TicketTypeCreateRequest request) {
        TicketTypeResponse response = ticketService.createTicketType(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/types/sync/{eventId}")
    public ResponseEntity<List<TicketTypeResponse>> syncTicketTypesForEvent(@PathVariable("eventId") String eventId,
                                                                             @RequestParam(value = "showtimeId", required = false) String showtimeId,
                                                                             @RequestBody List<TicketTypeCreateRequest> requests) {
        List<TicketTypeResponse> response = ticketService.syncTicketTypesForEvent(eventId, showtimeId, requests);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/event/{eventId}")
    public ResponseEntity<List<TicketTypeResponse>> getTicketTypesByEventId(@PathVariable("eventId") String eventId) {
        List<TicketTypeResponse> response = ticketService.getTicketTypesByEventId(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/event/{eventId}/all")
    public ResponseEntity<List<TicketTypeResponse>> getAllTicketTypesByEventId(@PathVariable("eventId") String eventId) {
        List<TicketTypeResponse> response = ticketService.getAllTicketTypesByEventId(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/{id}")
    public ResponseEntity<TicketTypeResponse> getTicketTypeById(@PathVariable("id") String id) {
        TicketTypeResponse response = ticketService.getTicketTypeById(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/showtime/{showtimeId}")
    public ResponseEntity<List<TicketTypeResponse>> getTicketTypesByShowtimeId(@PathVariable("showtimeId") String showtimeId) {
        List<TicketTypeResponse> response = ticketService.getTicketTypesByShowtimeId(showtimeId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/seat-layouts/event/{eventId}")
    public ResponseEntity<SeatLayoutResponse> getSeatLayoutByEventId(@PathVariable("eventId") String eventId) {
        SeatLayoutResponse response = ticketService.getSeatLayoutByEventId(eventId);
        if (response == null) {
            response = SeatLayoutResponse.builder()
                    .eventId(eventId)
                    .status("DRAFT")
                    .zones(List.of())
                    .build();
        }
        return ResponseEntity.ok(response);
    }

    @PutMapping("/seat-layouts/event/{eventId}/draft")
    public ResponseEntity<SeatLayoutResponse> saveSeatLayoutDraft(@PathVariable("eventId") String eventId,
                                                                    @RequestBody SeatLayoutRequest request) {
        SeatLayoutResponse response = ticketService.saveSeatLayoutDraft(eventId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/seat-layouts/event/{eventId}/publish")
    public ResponseEntity<SeatLayoutResponse> publishSeatLayout(@PathVariable("eventId") String eventId,
                                                                  @RequestBody SeatLayoutRequest request) {
        SeatLayoutResponse response = ticketService.publishSeatLayout(eventId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/seat-status")
    public ResponseEntity<Map<String, String>> getSeatStatus(@RequestParam("eventId") String eventId, @RequestParam(value = "showtimeId", required = false) String showtimeId) {
        return ResponseEntity.ok(ticketService.getSeatStatusMap(eventId, showtimeId));
    }
}