package com.haloticket.ticket_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class TicketCheckinDTO {
    private String checkinId;
    private String ticketId;
    private String qrCodeHash;
    private String seatName;
    private String ticketTypeName;
    private String status; // checkin status
    private String code;
    private LocalDateTime checkedInAt;
    private String showtimeId;
    private String ownerId;
}
