package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
                                                                             @RequestBody List<TicketTypeCreateRequest> requests) {
        List<TicketTypeResponse> response = ticketService.syncTicketTypesForEvent(eventId, requests);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/types/event/{eventId}")
    public ResponseEntity<List<TicketTypeResponse>> getTicketTypesByEventId(@PathVariable("eventId") String eventId) {
        List<TicketTypeResponse> response = ticketService.getTicketTypesByEventId(eventId);
        return ResponseEntity.ok(response);
    }
}