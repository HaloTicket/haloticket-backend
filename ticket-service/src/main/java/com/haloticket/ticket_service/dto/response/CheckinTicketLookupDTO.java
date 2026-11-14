package com.haloticket.ticket_service.dto.response;

import java.time.LocalDateTime;

/**
 * One ticket found by the door staff's search. The owner is masked; qrCodeHash is only filled for a ticket that can still
 * enter and only for a caller who may scan, so the manual check-in button can use the normal check-in call.
 */
public record CheckinTicketLookupDTO(String ticketId, String shortCode, String bookingId, String ownerMasked, String ticketTypeName,
                                     String seatName, String ticketKind, String zone, String row, String seat, String showtimeId,
                                     String ticketStatus, boolean checkedIn, LocalDateTime checkedInAt, String qrCodeHash) {}
