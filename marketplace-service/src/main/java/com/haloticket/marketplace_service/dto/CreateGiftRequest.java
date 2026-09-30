package com.haloticket.marketplace_service.dto;

import java.util.List;
public record CreateGiftRequest(String ticketId, String recipientEmail, String message, boolean acceptedTerms, List<String> ticketIds) {
    public CreateGiftRequest(String ticketId, String recipientEmail, String message, boolean acceptedTerms) {
        this(ticketId, recipientEmail, message, acceptedTerms, null);
    }
}
