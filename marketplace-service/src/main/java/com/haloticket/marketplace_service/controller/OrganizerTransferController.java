package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.dto.OrganizerGiftResponse;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.dto.OrganizerTransferSummaryResponse;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.TransferStatus;
import com.haloticket.marketplace_service.service.OrganizerTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplace/organizer/events/{eventId}")
@RequiredArgsConstructor
public class OrganizerTransferController {
    private final OrganizerTransferService organizerTransferService;

    @GetMapping("/transfer-summary")
    public OrganizerTransferSummaryResponse getSummary(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId) {
        return organizerTransferService.getSummary(authorization, eventId);
    }

    @GetMapping("/listings")
    public Page<OrganizerListingResponse> getListings(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ListingStatus status) {
        int boundedSize = Math.max(1, Math.min(100, size));
        return organizerTransferService.getListings(
                authorization, eventId,
                PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                status
        );
    }

    @GetMapping("/gifts")
    public Page<OrganizerGiftResponse> getGifts(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TransferStatus status) {
        int boundedSize = Math.max(1, Math.min(100, size));
        return organizerTransferService.getGifts(
                authorization, eventId,
                PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                status
        );
    }

    @GetMapping("/listings/{listingId}")
    public OrganizerListingResponse getListingDetail(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @PathVariable String listingId) {
        return organizerTransferService.getListingDetail(authorization, eventId, listingId);
    }

    @GetMapping("/gifts/{sagaId}")
    public OrganizerGiftResponse getGiftDetail(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @PathVariable String sagaId) {
        return organizerTransferService.getGiftDetail(authorization, eventId, sagaId);
    }
}
