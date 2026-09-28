package com.haloticket.ticket_service.dto.request;

import lombok.Data;

@Data
public class CheckinRequest {
    private String qrCodeHash;
    private String eventId;
    private String showtimeId;
    private String staffAssignmentId;
    private String deviceInfo;
}
