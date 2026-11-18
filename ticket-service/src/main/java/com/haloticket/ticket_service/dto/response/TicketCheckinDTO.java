package com.haloticket.ticket_service.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

/** Thông tin một lượt check-in của vé: vé, loại vé, vị trí ghế, trạng thái và thời điểm vào cửa. */
@Data
@Builder
public class TicketCheckinDTO {
    private String checkinId;
    private String ticketId;
    @ToString.Exclude
    private String qrCodeHash;
    private String seatName;
    private String ticketTypeName;
    private String status; // trạng thái check-in
    private String code;
    private LocalDateTime checkedInAt;
    private String showtimeId;
    private String ownerId;
    // vị trí ghế tách thành các trường riêng (`seatName` ở trên vẫn là câu tiếng Việt mà ứng dụng đang hiển thị)
    private String ticketKind; // SEATED, STANDING hoặc GENERAL (không có ghế)
    private String zone;
    private String row;
    private String seat;
}
