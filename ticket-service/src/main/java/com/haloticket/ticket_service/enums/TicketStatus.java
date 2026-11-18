package com.haloticket.ticket_service.enums;

/** Trạng thái vé: hợp lệ, đã dùng, đã chuyển cho người khác, bị thu hồi, đang khóa để bán lại hoặc đang khóa để chuyển nhượng. */
public enum TicketStatus {
    VALID, USED, TRANSFERED, REVOKED, LOCKED_FOR_RESALE, LOCKED_FOR_TRANSFER
}
