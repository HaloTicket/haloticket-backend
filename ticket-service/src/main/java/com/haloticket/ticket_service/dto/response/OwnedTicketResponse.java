package com.haloticket.ticket_service.dto.response;

import com.haloticket.ticket_service.entity.Ticket;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OwnedTicketResponse(String id, String bookingCode, String eventId, String ticketTypeId, String ticketTypeName,
                                  BigDecimal price, BigDecimal originalPrice, String acquisitionType, String status,
                                  String qrCode, Integer qrVersion, LocalDateTime issuedAt) {
    public static OwnedTicketResponse from(Ticket ticket) {
        BigDecimal price = ticket.getOriginalPrice();
        String typeId = null;
        String typeName = "Vé điện tử";
        if (ticket.getTicketType() != null) {
            typeId = ticket.getTicketType().getId();
            typeName = ticket.getTicketType().getName();
            if (price == null && ticket.getTicketType().getPrice() != null) {
                price = BigDecimal.valueOf(ticket.getTicketType().getPrice()).setScale(0, java.math.RoundingMode.HALF_UP);
            }
        }
        BigDecimal paidPrice = ticket.getPurchasePrice() == null ? price : ticket.getPurchasePrice();
        boolean isValid = ticket.getStatus() == com.haloticket.ticket_service.enums.TicketStatus.VALID;
        return new OwnedTicketResponse(ticket.getId(), ticket.getBookingId(), ticket.getEventId(), typeId, typeName, paidPrice,
                price, ticket.getAcquisitionType() == null ? "PRIMARY" : ticket.getAcquisitionType(),
                ticket.getStatus() == null ? null : ticket.getStatus().name(),
                isValid ? ticket.getQrCodeHash() : null,
                ticket.getQrVersion(), ticket.getIssuedAt());
    }
}
