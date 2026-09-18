package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.dto.*;
import com.haloticket.marketplace_service.service.TicketListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
public class TicketListingController {
    private final TicketListingService service;
    private final com.haloticket.marketplace_service.service.ResalePurchaseService purchaseService;

    @GetMapping("/tickets/{ticketId}/resale-eligibility")
    public ResponseEntity<?> eligibility(@RequestHeader("Authorization") String authorization, @PathVariable String ticketId) {
        return ResponseEntity.ok(service.eligibility(authorization, ticketId));
    }

    @PostMapping("/listings")
    public ResponseEntity<SagaResponse> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody CreateListingRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.startResale(authentication.getName(), authorization, key, request));
    }

    @PostMapping("/gifts")
    public ResponseEntity<SagaResponse> gift(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody CreateGiftRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.startGift(authentication.getName(), authorization, key, request));
    }

    @PostMapping("/gifts/{sagaId}/accept")
    public SagaResponse acceptGift(@PathVariable String sagaId, @RequestParam String token, Authentication authentication) {
        return service.acceptGift(authentication.getName(), sagaId, token);
    }

    @GetMapping("/sagas/{id}")
    public SagaResponse saga(@PathVariable String id, Authentication authentication) { return service.getSaga(authentication.getName(), id); }

    @GetMapping("/listings")
    public Page<ListingResponse> listings(Pageable pageable, Authentication authentication) {
        return service.publicListings(currentUserId(authentication), pageable);
    }

    @GetMapping("/events")
    public java.util.List<ResaleEventSummary> events() { return service.publicEvents(); }

    @GetMapping("/events/{eventId}/listings")
    public Page<ListingResponse> eventListings(@PathVariable String eventId, Pageable pageable, Authentication authentication) {
        return service.publicListingsByEvent(eventId, currentUserId(authentication), pageable);
    }

    @GetMapping("/listings/{listingId}")
    public ListingResponse listing(@PathVariable String listingId, Authentication authentication) {
        return service.publicListing(listingId, currentUserId(authentication));
    }

    @PostMapping("/listings/{listingId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable String listingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.reserveListing(authentication.getName(), key, listingId));
    }

    @PostMapping("/transactions/{transactionId}/payment")
    public PaymentInitiationResponse startPayment(@PathVariable String transactionId, Authentication authentication) {
        return purchaseService.startPayment(authentication.getName(), transactionId);
    }

    @GetMapping("/listings/me")
    public Page<ListingResponse> myListings(Pageable pageable, Authentication authentication) { return service.myListings(authentication.getName(), pageable); }

    @PostMapping("/listings/{listingId}/cancel")
    public SagaResponse cancelListing(@PathVariable String listingId, Authentication authentication) {
        return service.cancelListing(authentication.getName(), listingId);
    }

    private static String currentUserId(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
