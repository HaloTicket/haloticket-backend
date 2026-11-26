package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/tickets/events")
public class NotificationTicketOwnerController {
    private final EventAccessClient access;
    private final EventServiceClient events;
    private final TicketRepository tickets;
    private final TicketTypeRepository types;

    @GetMapping("/{eventId}/notification-owners")
    public List<Ownership> owners(@RequestHeader(name = "X-Internal-Token", required = false) String token,
            @PathVariable String eventId, @RequestParam String actorId,
            @RequestParam(defaultValue = "") String ticketTypeId, @RequestParam(defaultValue = "") String showtimeId,
            @RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "200") int limit) {
        if (!access.isInternal(token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        if (limit < 1 || limit > 200 || (!ticketTypeId.isEmpty() && !showtimeId.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TICKET_SELECTOR");
        }
        try {
            events.requireAnnouncementAccess(token, eventId, actorId, showtimeId);
        } catch (FeignException.Forbidden denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        } catch (FeignException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_OR_SHOWTIME_NOT_FOUND");
        } catch (FeignException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_SCOPE_UNAVAILABLE");
        }
        if (!ticketTypeId.isEmpty() && !types.existsByIdAndEventId(ticketTypeId, eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_TYPE_NOT_FOUND");
        }
        return tickets.findAnnouncementOwners(eventId, ticketTypeId, showtimeId, TicketStatus.VALID, TicketStatus.USED,
                        after, PageRequest.of(0, limit)).stream()
                .map(row -> new Ownership(row.getOwnerId(), row.getValid() == 1, row.getCheckedIn() == 1)).toList();
    }

    public record Ownership(String ownerId, boolean valid, boolean checkedIn) {}
}
