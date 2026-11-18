package com.haloticket.marketplace_service.dto;

import java.time.Instant;
import java.util.List;

/**
 * Thông tin một lượt tặng vé hiển thị cho ban tổ chức. Email người tặng và người nhận đã được che bớt.
 */
public record OrganizerGiftResponse(
    String sagaId,
    String senderEmailMasked,
    String recipientEmailMasked,
    String ticketTypeName,
    Instant sentAt,
    Instant expiresAt,
    String status,
    int quantity,
    List<String> ticketIds,
    Instant receivedAt,
    boolean requireEkyc,
    Boolean isRecipientEkycVerified
) {
    /**
     * Constructor có số lượng và danh sách vé; chưa có thời điểm nhận, không yêu cầu eKYC.
     *
     * @param sagaId mã saga của lượt tặng
     * @param senderEmailMasked email người tặng đã che
     * @param recipientEmailMasked email người nhận đã che
     * @param ticketTypeName tên loại vé
     * @param sentAt thời điểm gửi
     * @param expiresAt thời điểm hết hạn lời mời
     * @param status trạng thái lượt tặng
     * @param quantity số vé
     * @param ticketIds danh sách mã vé
     */
    public OrganizerGiftResponse(String sagaId, String senderEmailMasked, String recipientEmailMasked,
                                 String ticketTypeName, Instant sentAt, Instant expiresAt, String status,
                                 int quantity, List<String> ticketIds) {
        this(sagaId, senderEmailMasked, recipientEmailMasked, ticketTypeName, sentAt, expiresAt, status, quantity, ticketIds, null, false, null);
    }

    /**
     * Constructor rút gọn: mặc định 1 vé, danh sách vé rỗng, chưa có thời điểm nhận, không yêu cầu eKYC.
     *
     * @param sagaId mã saga của lượt tặng
     * @param senderEmailMasked email người tặng đã che
     * @param recipientEmailMasked email người nhận đã che
     * @param ticketTypeName tên loại vé
     * @param sentAt thời điểm gửi
     * @param expiresAt thời điểm hết hạn lời mời
     * @param status trạng thái lượt tặng
     */
    public OrganizerGiftResponse(String sagaId, String senderEmailMasked, String recipientEmailMasked,
                                 String ticketTypeName, Instant sentAt, Instant expiresAt, String status) {
        this(sagaId, senderEmailMasked, recipientEmailMasked, ticketTypeName, sentAt, expiresAt, status, 1, List.of(), null, false, null);
    }
}
