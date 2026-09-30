package com.haloticket.marketplace_service.dto;

public record OrganizerTransferSummaryResponse(
    long activeListings,
    long reservedListings,
    long completedListings,
    long pausedListings,
    long pendingGifts,
    long completedGifts,
    Long appliedResaleVersion,
    Long appliedGiftVersion
) {}
