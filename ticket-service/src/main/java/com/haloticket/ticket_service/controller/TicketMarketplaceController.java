package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketMarketplaceController {
    private final TicketRepository ticketRepository;

    @GetMapping("/{ticketId}/resale-eligibility")
    public ResaleEligibilityResponse resaleEligibility(@PathVariable String ticketId, @AuthenticationPrincipal Jwt jwt) {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String subject = jwt == null ? null : jwt.getSubject();
        if (subject == null || !subject.equals(ticket.getOwnerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        boolean eligible = ticket.getStatus() == TicketStatus.VALID;
        return new ResaleEligibilityResponse(ticket.getId(), ticket.getEventId(), eligible,
                eligible ? null : "TICKET_NOT_ELIGIBLE", ticket.getOriginalPrice(), null, null, null, null, null);
    }

    public record ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                             BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                             Long policyVersion, Instant closesAt, String termsVersion) {}
}
