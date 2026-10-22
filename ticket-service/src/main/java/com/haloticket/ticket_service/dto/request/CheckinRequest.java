package com.haloticket.ticket_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckinRequest {
    private String qrCodeHash;
    private String eventId;
    private String showtimeId;
    private String staffAssignmentId;
    private String deviceInfo;
}
