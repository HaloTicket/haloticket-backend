package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.authz.EventAccessView;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.CheckinPageDTO;
import com.haloticket.ticket_service.dto.response.CheckinSummaryDTO;
import com.haloticket.ticket_service.dto.response.CheckinTicketLookupDTO;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.service.CheckinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/tickets/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;
    private final EventAccessClient eventAccess;

    @PostMapping
    public ResponseEntity<TicketCheckinDTO> processCheckin(@RequestBody CheckinRequest request,
                                                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        EventAccessView access = eventAccess.require(request.getEventId(), "CHECKIN_SCAN", authorization);
        String verified = staffIdOf(access);
        if (request.getStaffAssignmentId() != null && !request.getStaffAssignmentId().equals(verified)) {
            log.info("[CHECKIN_STAFF_ID_REPLACED] event={} client sent a different staff id than the verified one", request.getEventId());
        }
        request.setStaffAssignmentId(verified);
        TicketCheckinDTO result = checkinService.processCheckin(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<List<TicketCheckinDTO>> getCheckinsByEvent(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        List<TicketCheckinDTO> results = checkinService.getCheckinsByEvent(eventId, showtimeId);
        return ResponseEntity.ok(results);
    }

    /** The people who entered, one page at a time (page counts from 0), newest first; showtimeId narrows it to one showtime. */
    @GetMapping("/events/{eventId}/page")
    public ResponseEntity<CheckinPageDTO> getCheckinPage(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.getCheckinPage(eventId, showtimeId, page, size));
    }

    /** Entered / total for the door, per showtime and per ticket type. Door staff may read it (same permission as the list). */
    @GetMapping("/events/{eventId}/summary")
    public ResponseEntity<CheckinSummaryDTO> getSummary(
            @PathVariable("eventId") String eventId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.getSummary(eventId));
    }

    /** Finds tickets by a fragment of the ticket code, order id, seat reference or ticket type name (at least two characters). */
    @GetMapping("/events/{eventId}/search")
    public ResponseEntity<List<CheckinTicketLookupDTO>> search(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        EventAccessView access = eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        return ResponseEntity.ok(checkinService.search(eventId, q, showtimeId, limit, access.has("CHECKIN_SCAN")));
    }

    private static String staffIdOf(EventAccessView access) {
        if (access.assignmentId() != null && !access.assignmentId().isBlank()) return access.assignmentId();
        String who = access.userId() != null ? access.userId() : "unknown";
        String prefix = access.platformAdmin() ? "PLATFORM_ADMIN:" : "EVENT_MEMBER:";
        return prefix + (who.length() > 8 ? who.substring(0, 8) : who);
    }
}
