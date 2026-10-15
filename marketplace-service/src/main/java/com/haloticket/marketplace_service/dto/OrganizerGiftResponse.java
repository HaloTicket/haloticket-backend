package com.haloticket.marketplace_service.dto;

import java.time.Instant;
import java.util.List;

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
    public OrganizerGiftResponse(String sagaId, String senderEmailMasked, String recipientEmailMasked,
                                 String ticketTypeName, Instant sentAt, Instant expiresAt, String status,
                                 int quantity, List<String> ticketIds) {
        this(sagaId, senderEmailMasked, recipientEmailMasked, ticketTypeName, sentAt, expiresAt, status, quantity, ticketIds, null, false, null);
    }

    public OrganizerGiftResponse(String sagaId, String senderEmailMasked, String recipientEmailMasked,
                                 String ticketTypeName, Instant sentAt, Instant expiresAt, String status) {
        this(sagaId, senderEmailMasked, recipientEmailMasked, ticketTypeName, sentAt, expiresAt, status, 1, List.of(), null, false, null);
    }
}
