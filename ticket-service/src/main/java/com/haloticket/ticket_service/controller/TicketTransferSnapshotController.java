package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Minimal service-only snapshot for policy reconciliation; never contains QR data. */
@RestController
@RequestMapping("/api/internal/tickets")
public class TicketTransferSnapshotController {
    private final TicketRepository tickets;
    private final String serviceToken;

    public TicketTransferSnapshotController(TicketRepository tickets,
            @Value("${transfer.internal-token:}") String serviceToken) {
        this.tickets = tickets;
        this.serviceToken = serviceToken;
    }

    @GetMapping("/{ticketId}/transfer-snapshot")
    public TransferSnapshot snapshot(@PathVariable String ticketId,
            @RequestHeader(value = "X-Transfer-Service-Token", required = false) String token) {
        if (serviceToken.isBlank() || token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var type = ticket.getTicketType();
        return new TransferSnapshot(ticket.getId(), ticket.getEventId(), ticket.getOwnerId(),
                ticket.getStatus(), ticket.getLockOperationId(), type == null ? null : type.getId(),
                ticket.getShowtimeId() != null ? ticket.getShowtimeId() : type == null ? null : type.getShowtimeId(),
                ticket.getOriginalPrice() != null ? ticket.getOriginalPrice()
                        : type == null || type.getPrice() == null ? null : BigDecimal.valueOf(type.getPrice()),
                ticket.getGiftTransferCount());
    }

    public record TransferSnapshot(String ticketId, String eventId, String ownerId, TicketStatus status,
            String lockOperationId, String ticketTypeId, String showtimeId, BigDecimal originalPrice,
            int giftTransferCount) {
        public TransferSnapshot(String ticketId, String eventId, String ownerId, TicketStatus status,
                String lockOperationId, String ticketTypeId, String showtimeId, BigDecimal originalPrice) {
            this(ticketId, eventId, ownerId, status, lockOperationId, ticketTypeId, showtimeId, originalPrice, 0);
        }
    }
}
