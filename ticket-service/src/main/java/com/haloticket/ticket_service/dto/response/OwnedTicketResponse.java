package com.haloticket.ticket_service.dto.response;

import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.Seat;
import com.haloticket.ticket_service.enums.TicketStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** Thông tin một vé thuộc sở hữu của người dùng: loại vé, giá, trạng thái, mã QR (chỉ khi vé còn hiệu lực) và vị trí ghế. */
public record OwnedTicketResponse(String id, String bookingCode, String eventId, String ticketTypeId, String ticketTypeName,
                                  BigDecimal price, BigDecimal originalPrice, String acquisitionType, String status,
                                  String qrCode, Integer qrVersion, LocalDateTime issuedAt, String seatName,
                                  String bookingId, String showtimeId, String section, String row, String seatNumber) {
    /**
     * Chuyển entity {@code Ticket} sang DTO. Giá gốc lấy từ vé, thiếu thì lấy giá loại vé; mã QR chỉ trả khi vé {@code VALID};
     * tên ghế dựng từ ghế thật, nếu không có thì từ nhãn hàng và số ghế lưu trên vé.
     *
     * @param ticket vé cần chuyển đổi
     * @return thông tin vé dạng DTO
     */
    public static OwnedTicketResponse from(Ticket ticket) {
        BigDecimal price = ticket.getOriginalPrice();
        String typeId = null;
        String typeName = "Vé điện tử";
        if (ticket.getTicketType() != null) {
            typeId = ticket.getTicketType().getId();
            typeName = ticket.getTicketType().getName();
            if (price == null && ticket.getTicketType().getPrice() != null) {
                price = BigDecimal.valueOf(ticket.getTicketType().getPrice()).setScale(0, RoundingMode.HALF_UP);
            }
        }
        BigDecimal paidPrice = ticket.getPurchasePrice() == null ? price : ticket.getPurchasePrice();
        boolean isValid = ticket.getStatus() == TicketStatus.VALID;
        
        String seatName = null;
        Seat seat = ticket.getSeat();
        if (seat != null) {
            if (seat.getSeatRow() != null) {
                seatName = "Hàng " + seat.getSeatRow().getLabel() + " - Ghế " + seat.getSeatNumber();
            } else {
                seatName = "Số " + seat.getSeatNumber();
            }
        } else if (ticket.getSeatNumberLabel() != null) {
            seatName = ticket.getSeatRowLabel() == null ? "Ghế " + ticket.getSeatNumberLabel()
                    : "Hàng " + ticket.getSeatRowLabel() + " - Ghế " + ticket.getSeatNumberLabel();
        }

        return new OwnedTicketResponse(ticket.getId(), ticket.getBookingId(), ticket.getEventId(), typeId, typeName, paidPrice,
                price, ticket.getAcquisitionType() == null ? "PRIMARY" : ticket.getAcquisitionType(),
                ticket.getStatus() == null ? null : ticket.getStatus().name(),
                isValid ? ticket.getQrCodeHash() : null,
                ticket.getQrVersion(), ticket.getIssuedAt(), seatName,
                ticket.getBookingId(), ticket.getShowtimeId() != null ? ticket.getShowtimeId()
                        : ticket.getTicketType() == null ? null : ticket.getTicketType().getShowtimeId(),
                seat == null || seat.getSeatRow() == null || seat.getSeatRow().getSeatZone() == null
                        ? ticket.getSeatSection() : seat.getSeatRow().getSeatZone().getName(),
                seat == null || seat.getSeatRow() == null ? ticket.getSeatRowLabel() : seat.getSeatRow().getLabel(),
                seat == null ? ticket.getSeatNumberLabel() : seat.getSeatNumber());
    }
}
