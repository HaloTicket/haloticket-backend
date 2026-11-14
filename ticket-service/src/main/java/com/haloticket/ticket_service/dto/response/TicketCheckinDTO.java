package com.haloticket.ticket_service.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@Builder
public class TicketCheckinDTO {
    private String checkinId;
    private String ticketId;
    @ToString.Exclude
    private String qrCodeHash;
    private String seatName;
    private String ticketTypeName;
    private String status; // checkin status
    private String code;
    private LocalDateTime checkedInAt;
    private String showtimeId;
    private String ownerId;
    // the seat as separate fields (seatName above stays the Vietnamese sentence the app already shows)
    private String ticketKind; // SEATED, STANDING or GENERAL (no seat)
    private String zone;
    private String row;
    private String seat;
}
