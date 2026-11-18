package com.haloticket.marketplace_service.dto;

/**
 * Số liệu tổng hợp về chuyển nhượng vé của một sự kiện (số tin theo trạng thái, số lượt tặng)
 * và phiên bản chính sách bán lại, tặng vé đã áp dụng.
 */
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
