package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.authz.EventAccessView;
import com.haloticket.ticket_service.authz.EventScopeProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
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
    private final EventScopeProperties scope;

    @PostMapping
    public ResponseEntity<TicketCheckinDTO> processCheckin(@RequestBody CheckinRequest request,
                                                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (scope.checkinOn()) {
            EventAccessView access = eventAccess.require(request.getEventId(), "CHECKIN_SCAN", authorization);
            String verified = staffIdOf(access);
            if (request.getStaffAssignmentId() != null && !request.getStaffAssignmentId().equals(verified)) {
                log.info("[CHECKIN_STAFF_ID_REPLACED] event={} client sent a different staff id than the verified one", request.getEventId());
            }
            request.setStaffAssignmentId(verified);
        }
        TicketCheckinDTO result = checkinService.processCheckin(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<List<TicketCheckinDTO>> getCheckinsByEvent(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "showtimeId", required = false) String showtimeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (scope.checkinOn()) eventAccess.require(eventId, "CHECKIN_VIEW", authorization);
        List<TicketCheckinDTO> results = checkinService.getCheckinsByEvent(eventId, showtimeId);
        return ResponseEntity.ok(results);
    }

    private static String staffIdOf(EventAccessView access) {
        if (access.assignmentId() != null && !access.assignmentId().isBlank()) return access.assignmentId();
        String who = access.userId() != null ? access.userId() : "unknown";
        String prefix = access.platformAdmin() ? "PLATFORM_ADMIN:" : "EVENT_MEMBER:";
        return prefix + (who.length() > 8 ? who.substring(0, 8) : who);
    }
}
