package com.haloticket.marketplace_service.dto;

public record CreateGiftRequest(String ticketId, String recipientEmail, String message, boolean acceptedTerms) {}
