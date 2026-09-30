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
    List<String> ticketIds
) {
    public OrganizerGiftResponse(String sagaId, String senderEmailMasked, String recipientEmailMasked,
                                 String ticketTypeName, Instant sentAt, Instant expiresAt, String status) {
        this(sagaId, senderEmailMasked, recipientEmailMasked, ticketTypeName, sentAt, expiresAt, status, 1, List.of());
    }
}
