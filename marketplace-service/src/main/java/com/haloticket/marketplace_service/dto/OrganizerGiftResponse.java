package com.haloticket.marketplace_service.dto;

import java.time.Instant;

public record OrganizerGiftResponse(
    String sagaId,
    String senderEmailMasked,
    String recipientEmailMasked,
    String ticketTypeName,
    Instant sentAt,
    Instant expiresAt,
    String status
) {}
