package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.service.CheckinService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @PostMapping
    public ResponseEntity<TicketCheckinDTO> processCheckin(@RequestBody CheckinRequest request) {
        System.out.println("Received CheckinRequest: " + request);
        TicketCheckinDTO result = checkinService.processCheckin(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<List<TicketCheckinDTO>> getCheckinsByEvent(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId) {
        List<TicketCheckinDTO> results = checkinService.getCheckinsByEvent(eventId, showtimeId);
        return ResponseEntity.ok(results);
    }
}
