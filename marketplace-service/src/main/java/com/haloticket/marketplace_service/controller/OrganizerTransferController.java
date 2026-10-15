package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.dto.OrganizerGiftResponse;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.dto.OrganizerTransferSummaryResponse;
import com.haloticket.marketplace_service.dto.SagaResponse;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.TransferStatus;
import com.haloticket.marketplace_service.service.OrganizerTransferService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/marketplace/organizer/events/{eventId}")
public class OrganizerTransferController {
    private final OrganizerTransferService organizerTransferService;

    public OrganizerTransferController(OrganizerTransferService organizerTransferService) {
        this.organizerTransferService = organizerTransferService;
    }

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

    @PostMapping("/listings/{listingId}/moderate")
    public SagaResponse moderateListing(@RequestHeader("Authorization") String authorization,
                                         @PathVariable String eventId, @PathVariable String listingId,
                                         @RequestBody ModerationRequest request, Authentication authentication) {
        if (request.action() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MODERATION_ACTION_REQUIRED");
        return organizerTransferService.moderateListing(authorization, authentication.getName(), eventId, listingId,
                request.action() == ModerationAction.APPROVE, request.reason());
    }

    @PostMapping("/listings/{listingId}/cancel")
    public SagaResponse cancelListing(@RequestHeader("Authorization") String authorization,
                                      @PathVariable String eventId, @PathVariable String listingId,
                                      @RequestBody(required = false) CancellationRequest request, Authentication authentication) {
        return organizerTransferService.cancelListing(authorization, authentication.getName(), eventId, listingId,
                request == null ? null : request.reason());
    }

    @PostMapping("/gifts/{sagaId}/cancel")
    public SagaResponse cancelGift(@RequestHeader("Authorization") String authorization,
                                   @PathVariable String eventId, @PathVariable String sagaId,
                                   @RequestBody(required = false) CancellationRequest request, Authentication authentication) {
        return organizerTransferService.cancelGift(authorization, authentication.getName(), eventId, sagaId,
                request == null ? null : request.reason());
    }

    public enum ModerationAction { APPROVE, REJECT }
    public record ModerationRequest(ModerationAction action, String reason) {}
    public record CancellationRequest(String reason) {}
}
