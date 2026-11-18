package com.haloticket.ticket_service.dto.response;

import java.time.LocalDateTime;

/**
 * Một vé tìm thấy qua tìm kiếm của nhân viên cửa. Chủ vé được che bớt; {@code qrCodeHash} chỉ được điền cho vé còn có thể vào
 * và chỉ cho người gọi có quyền quét, để nút check-in thủ công dùng được lời gọi check-in thông thường.
 */
public record CheckinTicketLookupDTO(String ticketId, String shortCode, String bookingId, String ownerMasked, String ticketTypeName,
                                     String seatName, String ticketKind, String zone, String row, String seat, String showtimeId,
                                     String ticketStatus, boolean checkedIn, LocalDateTime checkedInAt, String qrCodeHash) {}
