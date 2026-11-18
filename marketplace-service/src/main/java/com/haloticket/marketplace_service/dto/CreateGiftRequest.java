package com.haloticket.marketplace_service.dto;

import java.util.List;
/**
 * Yêu cầu tặng vé: một vé ({@code ticketId}) hoặc nhiều vé ({@code ticketIds}) cho người nhận theo email,
 * kèm lời nhắn và xác nhận đã đồng ý điều khoản.
 */
public record CreateGiftRequest(String ticketId, String recipientEmail, String message, boolean acceptedTerms, List<String> ticketIds) {
    /**
     * Constructor tặng một vé: không có danh sách {@code ticketIds}.
     *
     * @param ticketId mã vé cần tặng
     * @param recipientEmail email người nhận
     * @param message lời nhắn gửi kèm
     * @param acceptedTerms đã đồng ý điều khoản tặng vé hay chưa
     */
    public CreateGiftRequest(String ticketId, String recipientEmail, String message, boolean acceptedTerms) {
        this(ticketId, recipientEmail, message, acceptedTerms, null);
    }
}
