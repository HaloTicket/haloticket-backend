package com.haloticket.ticket_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.NoArgsConstructor;

/** Yêu cầu check-in: mã băm QR của vé, sự kiện, suất diễn, nhân viên quét và thông tin thiết bị. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckinRequest {
    @ToString.Exclude
    private String qrCodeHash;
    private String eventId;
    private String showtimeId;
    private String staffAssignmentId;
    private String deviceInfo;
}
