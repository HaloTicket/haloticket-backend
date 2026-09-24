package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.UserServiceClient;
import com.haloticket.marketplace_service.dto.*;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import lombok.RequiredArgsConstructor;
import feign.FeignException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TicketListingService {
    private final TicketListingRepository listingRepository;
    private final MarketplaceSagaRepository sagaRepository;
    private final OutboxEventRepository outboxRepository;
    private final TicketTransferRepository transferRepository;
    private final MarketplaceTransactionRepository transactionRepository;
    private final TicketServiceClient ticketServiceClient;
    private final EventServiceClient eventServiceClient;
    private final UserServiceClient userServiceClient;
    private final ObjectMapper objectMapper;

    public TicketServiceClient.ResaleEligibilityResponse eligibility(String authorization, String ticketId) {
        TicketServiceClient.ResaleEligibilityResponse ticket = ticketServiceClient.getResaleEligibility(authorization, ticketId);
        if (ticket == null || !ticket.eligible()) return ticket;

        EventServiceClient.ResalePolicyResponse policy;
        try {
            policy = eventServiceClient.getResalePolicy(authorization, ticket.eventId());
        } catch (FeignException.NotFound exception) {
            return new TicketServiceClient.ResaleEligibilityResponse(
                    ticket.ticketId(), ticket.eventId(), false, "RESALE_POLICY_NOT_CONFIGURED", ticket.originalPrice(),
                    null, null, null, null, null,
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat());
        }
        boolean policyOpen = policy != null
                && policy.enabled()
                && policy.minPricePercent() != null
                && policy.maxPricePercent() != null
                && (policy.closesAt() == null || !policy.closesAt().isBefore(Instant.now()));
        if (!policyOpen) {
            String reasonCode = policy != null && policy.enabled() && policy.closesAt() != null
                    && policy.closesAt().isBefore(Instant.now()) ? "RESALE_DEADLINE_PASSED" : "RESALE_DISABLED";
            return new TicketServiceClient.ResaleEligibilityResponse(
                    ticket.ticketId(), ticket.eventId(), false, reasonCode, ticket.originalPrice(),
                    null, null, policy == null ? null : policy.version(),
                    policy == null ? null : policy.closesAt(), policy == null ? null : policy.termsVersion(),
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat());
        }

        BigDecimal originalPrice = ticket.originalPrice() == null ? BigDecimal.ZERO : ticket.originalPrice();
        BigDecimal minPrice = originalPrice.multiply(policy.minPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.CEILING);
        BigDecimal maxPrice = originalPrice.multiply(policy.maxPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
        return new TicketServiceClient.ResaleEligibilityResponse(
                ticket.ticketId(), ticket.eventId(), true, null, originalPrice,
                minPrice, maxPrice, policy.version(), policy.closesAt(), policy.termsVersion(),
                ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat());
    }

    @Transactional
    public SagaResponse startResale(String actorId, String authorization, String idempotencyKey, CreateListingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        if (request == null || request.ticketId() == null || request.ticketId().isBlank() || request.price() == null || request.price().scale() > 0 || request.price().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_INVALID");
        }
        String hash = hash(request);
        Optional<MarketplaceSaga> existing = sagaRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            return toResponse(existing.get());
        }

        UserServiceClient.ResaleSellerEligibility sellerEligibility;
        try { sellerEligibility = userServiceClient.getResaleEligibility(authorization); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_SERVICE_UNAVAILABLE", exception); }
        if (sellerEligibility == null || !sellerEligibility.eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EKYC_REQUIRED");
        }

        TicketServiceClient.ResaleEligibilityResponse eligibility;
        try { eligibility = eligibility(authorization, request.ticketId()); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ELIGIBILITY_SERVICE_UNAVAILABLE", exception); }
        if (eligibility == null || !eligibility.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
        BigDecimal minPrice = eligibility.minPrice();
        BigDecimal maxPrice = eligibility.maxPrice();
        if (request.price().compareTo(minPrice) < 0 || request.price().compareTo(maxPrice) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_OUT_OF_RANGE");
        }
        if (request.policyVersion() != null && eligibility.policyVersion() != null && !request.policyVersion().equals(eligibility.policyVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED");
        }
        Instant now = Instant.now();
        String sagaId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId); payload.put("ticketId", request.ticketId()); payload.put("sellerId", actorId);
        payload.put("price", request.price()); payload.put("eventId", eligibility.eventId()); payload.put("originalPrice", eligibility.originalPrice());
        payload.put("minPrice", minPrice); payload.put("maxPrice", maxPrice); payload.put("policyVersion", eligibility.policyVersion());
        payload.put("closesAt", eligibility.closesAt());
        payload.put("termsVersion", request.termsVersion() != null ? request.termsVersion() : eligibility.termsVersion());
        payload.put("ticketTypeName", eligibility.ticketTypeName()); payload.put("section", eligibility.section());
        payload.put("row", eligibility.row()); payload.put("seat", eligibility.seat());
        int quantity = 1;
        payload.put("quantity", quantity);
        payload.put("allowRetail", retailAllowed(quantity, request.allowRetail()));
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("RESALE_LISTING").ticketId(request.ticketId()).actorId(actorId)
                    .state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED").payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED")
                    .topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(Map.of("messageId", UUID.randomUUID().toString(), "sagaId", sagaId, "ticketId", request.ticketId(), "sellerId", actorId, "purpose", "RESALE")))
                    .status(OutboxStatus.PENDING).retryCount(0).build());
            return toResponse(saga);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create resale saga", exception); }
    }

    @Transactional(readOnly = true)
    public SagaResponse getSaga(String actorId, String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!saga.getActorId().equals(actorId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return toResponse(saga);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListings(Pageable pageable) {
        return publicListings(null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListings(String currentUserId, Pageable pageable) {
        return listingRepository.findPublicActive(ListingStatus.ACTIVE, Instant.now(), pageable)
                .map(listing -> ListingResponse.from(listing, currentUserId));
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListingsByEvent(String eventId, Pageable pageable) {
        return publicListingsByEvent(eventId, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> publicListingsByEvent(String eventId, String currentUserId, Pageable pageable) {
        return listingRepository.findPublicActiveByEventId(eventId, ListingStatus.ACTIVE, Instant.now(), pageable)
                .map(listing -> ListingResponse.from(listing, currentUserId));
    }

    @Transactional(readOnly = true)
    public List<ResaleEventSummary> publicEvents() {
        return listingRepository.summarizePublicEvents(ListingStatus.ACTIVE, Instant.now());
    }

    @Transactional(readOnly = true)
    public ListingResponse publicListing(String listingId) {
        return publicListing(listingId, null);
    }

    @Transactional(readOnly = true)
    public ListingResponse publicListing(String listingId, String currentUserId) {
        TicketListing listing = listingRepository.findById(listingId)
                .filter(item -> item.getStatus() == ListingStatus.ACTIVE && item.getPublishedAt() != null
                        && (item.getExpiresAt() == null || item.getExpiresAt().isAfter(Instant.now())))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_AVAILABLE"));
        return ListingResponse.from(listing, currentUserId);
    }

    @Transactional
    public ReservationResponse reserveListing(String buyerId, String idempotencyKey, String listingId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        Optional<MarketplaceTransaction> existing = transactionRepository.findByPaymentReference(idempotencyKey);
        if (existing.isPresent()) {
            MarketplaceTransaction transaction = existing.get();
            if (!transaction.getBuyerId().equals(buyerId) || !transaction.getTicketListing().getId().equals(listingId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            }
            return reservationResponse(transaction);
        }

        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        Instant now = Instant.now();
        if (listing.getStatus() == ListingStatus.RESERVED
                && buyerId.equals(listing.getReservedBy())
                && listing.getReservedUntil() != null
                && listing.getReservedUntil().isAfter(now)) {
            return transactionRepository.findFirstByTicketListing_IdAndBuyerIdAndStatusIn(
                            listingId, buyerId, List.of(
                                    MarketplaceTransactionStatus.PENDING_PAYMENT,
                                    MarketplaceTransactionStatus.PAYMENT_PROCESSING))
                    .map(this::reservationResponse)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_TRANSACTION_NOT_FOUND"));
        }
        if (listing.getStatus() != ListingStatus.ACTIVE || listing.getPublishedAt() == null
                || (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_AVAILABLE");
        }
        if (listing.getSellerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BUYER_IS_SELLER");
        }

        Instant reservationExpiresAt = now.plusSeconds(10 * 60);
        listing.setStatus(ListingStatus.RESERVED);
        listing.setReservedBy(buyerId);
        listing.setReservedUntil(reservationExpiresAt);
        listingRepository.save(listing);
        MarketplaceTransaction transaction = MarketplaceTransaction.builder()
                .ticketListing(listing).buyerId(buyerId).sellerId(listing.getSellerId())
                .amount(listing.getPrice().multiply(BigDecimal.valueOf(Math.max(1, listing.getQuantity() == null ? 1 : listing.getQuantity()))))
                .currency("VND").status(MarketplaceTransactionStatus.PENDING_PAYMENT)
                .paymentReference(idempotencyKey).reservationExpiresAt(reservationExpiresAt).build();
        return reservationResponse(transactionRepository.save(transaction));
    }

    @Transactional
    public void expireReservations() {
        Instant now = Instant.now();
        for (TicketListing listing : listingRepository.findByStatusAndReservedUntilBefore(ListingStatus.RESERVED, now)) {
            transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PENDING_PAYMENT)
                    .ifPresent(transaction -> {
                        transaction.setStatus(MarketplaceTransactionStatus.EXPIRED);
                        transaction.setCancelledAt(now);
                        transactionRepository.save(transaction);
                    });
            listing.setStatus(ListingStatus.ACTIVE);
            listing.setReservedBy(null);
            listing.setReservedUntil(null);
            listingRepository.save(listing);
        }
    }

    @Transactional
    public void cancelReservation(String buyerId, String listingId) {
        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElse(null);
        if (listing != null && listing.getStatus() == ListingStatus.RESERVED) {
            if (buyerId != null && !buyerId.equals(listing.getReservedBy())) {
                return;
            }
            Instant now = Instant.now();
            transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PENDING_PAYMENT)
                    .ifPresent(transaction -> {
                        transaction.setStatus(MarketplaceTransactionStatus.CANCELLED);
                        transaction.setCancelledAt(now);
                        transactionRepository.save(transaction);
                    });
            listing.setStatus(ListingStatus.ACTIVE);
            listing.setReservedBy(null);
            listing.setReservedUntil(null);
            listingRepository.save(listing);
        }
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> myListings(String actorId, Pageable pageable) { return listingRepository.findBySellerId(actorId, pageable).map(ListingResponse::from); }

    static boolean retailAllowed(int quantity, Boolean requested) {
        return quantity <= 1 || !Boolean.FALSE.equals(requested);
    }

    @Transactional
    public SagaResponse cancelListing(String actorId, String listingId) {
        TicketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        if (!listing.getSellerId().equals(actorId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LISTING_NOT_OWNED");
        if (listing.getStatus() != ListingStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_CANCELLABLE");

        MarketplaceSaga saga = sagaRepository.findById(listing.getSagaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_SAGA_NOT_FOUND"));
        listing.setStatus(ListingStatus.CANCELLED);
        listingRepository.save(listing);
        saga.setState(SagaState.COMPENSATING);
        saga.setStep("SELLER_CANCELLATION_RELEASE_REQUESTED");
        saga.setLastError("SELLER_CANCELLED");
        saga.setNextRetryAt(Instant.now().plusSeconds(10));
        sagaRepository.save(saga);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("messageId", UUID.randomUUID().toString());
        command.put("sagaId", saga.getId());
        command.put("operationId", saga.getId());
        command.put("ticketId", listing.getTicketId());
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_LISTING").aggregateId(listingId)
                    .eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1")
                    .payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not create listing cancellation command", exception);
        }
        return toResponse(saga);
    }

    @Transactional
    public void activateFromTicketLock(String sagaId) {
        handleTicketLockResult(sagaId, true);
    }

    @Transactional
    public void handleTicketLockResult(String sagaId, boolean locked) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || saga.getState() != SagaState.WAITING_LOCK) return;
        if (!locked) {
            saga.setState(SagaState.FAILED); saga.setStep("LOCK_REJECTED"); saga.setLastError("TICKET_NOT_ELIGIBLE");
            sagaRepository.save(saga);
            if ("GIFT_TRANSFER".equals(saga.getType())) {
                transferRepository.findBySagaId(sagaId).ifPresent(transfer -> {
                    transfer.setStatus(TransferStatus.CANCELLED); transfer.setCancellationReason("TICKET_NOT_ELIGIBLE"); transferRepository.save(transfer);
                });
            }
            return;
        }
        try {
            var node = objectMapper.readTree(saga.getPayload());
            if ("GIFT_TRANSFER".equals(saga.getType())) {
                TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow();
                String token = UUID.randomUUID().toString();
                transfer.setInvitationTokenHash(hashToken(token));
                transfer.setInvitationExpiresAt(Instant.now().plusSeconds(24 * 60 * 60));
                transferRepository.save(transfer);
                saga.setDeadlineAt(transfer.getInvitationExpiresAt());
                saga.setState(SagaState.WAITING_RECIPIENT); saga.setStep("INVITATION_CREATED"); sagaRepository.save(saga);
                Map<String, Object> invitation = new LinkedHashMap<>();
                invitation.put("sagaId", sagaId);
                invitation.put("ticketId", transfer.getTicketId());
                invitation.put("recipientEmail", transfer.getRecipientEmail());
                invitation.put("recipientUserId", transfer.getToUserId());
                invitation.put("message", transfer.getMessage());
                invitation.put("invitationToken", token);
                invitation.put("expiresAt", transfer.getInvitationExpiresAt());
                invitation.put("senderUserId", transfer.getFromUserId());
                String senderName = "Một người bạn trên HaloTicket";
                try {
                    var sender = userServiceClient.getUserDisplayName(transfer.getFromUserId());
                    if (sender != null && sender.fullName() != null && !sender.fullName().isBlank()) {
                        senderName = sender.fullName();
                    }
                } catch (Exception ignored) {}
                invitation.put("senderName", senderName);
                invitation.put("ticketTypeName", node.path("ticketTypeName").asText(""));
                invitation.put("section", node.path("section").asText(""));
                invitation.put("row", node.path("row").asText(""));
                invitation.put("seat", node.path("seat").asText(""));
                invitation.put("eventId", node.path("eventId").asText(""));
                outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_INVITATION_CREATED")
                        .topic("haloticket.marketplace.gift.invited.v1").payload(objectMapper.writeValueAsString(invitation)).status(OutboxStatus.PENDING).retryCount(0).build());
                return;
            }
            Instant expiry = node.hasNonNull("closesAt") ? Instant.parse(node.get("closesAt").asText()) : saga.getDeadlineAt();
            TicketListing listing = TicketListing.builder().ticketId(node.get("ticketId").asText()).sellerId(node.get("sellerId").asText())
                    .price(new BigDecimal(node.get("price").asText())).originalPrice(decimal(node,"originalPrice")).minPriceSnapshot(decimal(node,"minPrice"))
                    .maxPriceSnapshot(decimal(node,"maxPrice")).eventId(text(node,"eventId")).status(ListingStatus.ACTIVE).expiresAt(expiry)
                    .ticketTypeNameSnapshot(text(node,"ticketTypeName")).sectionSnapshot(text(node,"section"))
                    .rowSnapshot(text(node,"row")).seatSnapshot(text(node,"seat"))
                    .quantity(node.path("quantity").asInt(1))
                    .allowRetail(node.path("quantity").asInt(1) == 1 || node.path("allowRetail").asBoolean(true))
                    .publishedAt(Instant.now()).policyVersion(node.hasNonNull("policyVersion") ? node.get("policyVersion").asLong() : null)
                    .termsVersion(text(node,"termsVersion")).sagaId(sagaId).build();
            listingRepository.save(listing);
            saga.setState(SagaState.COMPLETED); saga.setStep("LISTING_ACTIVE"); saga.setBusinessId(listing.getId());
            sagaRepository.save(saga);
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_LISTING").aggregateId(listing.getId()).eventType("RESALE_LISTING_ACTIVATED")
                    .topic("haloticket.marketplace.listing.activated.v1").payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not activate resale listing", exception); }
    }

    @Transactional
    public SagaResponse startGift(String actorId, String authorization, String idempotencyKey, CreateGiftRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        if (request == null || request.ticketId() == null || request.ticketId().isBlank() || request.recipientEmail() == null || request.recipientEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_REQUIRED");
        }
        String hash = hash(request);
        Optional<MarketplaceSaga> existing = sagaRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            return toResponse(existing.get());
        }
        String recipientEmail = request.recipientEmail().trim().toLowerCase(java.util.Locale.ROOT);
        UserServiceClient.GiftRecipient recipient;
        try { recipient = userServiceClient.getGiftRecipient(authorization, recipientEmail); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_NOT_FOUND", exception); }
        if (recipient == null || recipient.userId() == null || recipient.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_NOT_FOUND");
        }
        if (actorId.equals(recipient.userId())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_GIFT_SELF");
        TicketServiceClient.ResaleEligibilityResponse eligibility;
        try { eligibility = ticketServiceClient.getResaleEligibility(authorization, request.ticketId()); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_SERVICE_UNAVAILABLE", exception); }
        if (eligibility == null || !eligibility.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
        String sagaId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId);
        payload.put("ticketId", request.ticketId());
        payload.put("fromUserId", actorId);
        payload.put("recipientEmail", recipientEmail);
        payload.put("recipientUserId", recipient.userId());
        payload.put("message", request.message());
        payload.put("eventId", eligibility.eventId());
        payload.put("ticketTypeName", eligibility.ticketTypeName());
        payload.put("section", eligibility.section());
        payload.put("row", eligibility.row());
        payload.put("seat", eligibility.seat());
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("GIFT_TRANSFER").ticketId(request.ticketId()).actorId(actorId).state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED")
                    .payload(objectMapper.writeValueAsString(payload)).idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            TicketTransfer transfer = TicketTransfer.builder().ticketId(request.ticketId()).fromUserId(actorId).toUserId(recipient.userId()).recipientEmail(recipientEmail).message(request.message()).type(TransferType.GIFT).status(TransferStatus.PENDING).sagaId(sagaId).build();
            transferRepository.save(transfer); saga.setBusinessId(transfer.getId()); sagaRepository.save(saga);
            Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", request.ticketId()); command.put("sellerId", actorId); command.put("purpose", "GIFT");
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED").topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
            return toResponse(saga);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create gift saga", exception); }
    }

    @Transactional
    public SagaResponse acceptGift(String actorId, String authorization, String sagaId, String token) {
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (saga.getState() != SagaState.WAITING_RECIPIENT || transfer.getInvitationExpiresAt() == null || transfer.getInvitationExpiresAt().isBefore(Instant.now())
                || (token != null && !hashToken(token).equals(transfer.getInvitationTokenHash()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_INVITATION_INVALID");
        }
        if (!actorId.equals(transfer.getToUserId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_RECIPIENT_MISMATCH");
        UserServiceClient.CurrentUser currentUser = userServiceClient.getCurrentUser(authorization);
        if (currentUser == null || !transfer.getRecipientEmail().equalsIgnoreCase(currentUser.email())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_RECIPIENT_MISMATCH");
        transfer.setToUserId(actorId); transferRepository.save(transfer); saga.setState(SagaState.WAITING_TRANSFER); saga.setStep("TRANSFER_REQUESTED"); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", transfer.getTicketId()); command.put("fromUserId", transfer.getFromUserId()); command.put("toUserId", actorId); command.put("lockOperationId", sagaId);
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("TICKET_OWNERSHIP_TRANSFER_REQUESTED").topic("haloticket.ticket.ownership.transfer.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create transfer command", exception); }
        return toResponse(saga);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> giftInvitation(String actorId, String sagaId, String token) {
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!actorId.equals(transfer.getToUserId()) || (token != null && !hashToken(token).equals(transfer.getInvitationTokenHash()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_RECIPIENT_MISMATCH");
        }
        try {
            var payload = objectMapper.readTree(saga.getPayload());
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : java.util.List.of("ticketId", "eventId", "ticketTypeName", "section", "row", "seat", "message")) {
                result.put(key, payload.path(key).asText(""));
            }
            result.put("recipientEmail", transfer.getRecipientEmail());
            result.put("expiresAt", transfer.getInvitationExpiresAt());
            result.put("state", saga.getState());
            String fromUserId = transfer.getFromUserId();
            result.put("senderUserId", fromUserId);
            try {
                var sender = userServiceClient.getUserDisplayName(fromUserId);
                result.put("senderName", sender != null ? sender.fullName() : null);
            } catch (Exception ignored) { result.put("senderName", null); }
            return result;
        } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> myGiftInvitations(String actorId) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (TicketTransfer transfer : transferRepository.findByToUserIdAndStatusOrderByCreatedAtDesc(actorId, TransferStatus.PENDING)) {
            if (transfer.getInvitationExpiresAt() == null || transfer.getInvitationExpiresAt().isBefore(Instant.now())) continue;
            MarketplaceSaga saga = sagaRepository.findById(transfer.getSagaId()).orElse(null);
            if (saga == null || saga.getState() != SagaState.WAITING_RECIPIENT) continue;
            try {
                var payload = objectMapper.readTree(saga.getPayload());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sagaId", saga.getId());
                item.put("eventId", payload.path("eventId").asText(""));
                item.put("ticketTypeName", payload.path("ticketTypeName").asText("Vé sự kiện"));
                item.put("section", payload.path("section").asText(""));
                item.put("row", payload.path("row").asText(""));
                item.put("seat", payload.path("seat").asText(""));
                item.put("expiresAt", transfer.getInvitationExpiresAt());
                try {
                    var sender = userServiceClient.getUserDisplayName(transfer.getFromUserId());
                    item.put("senderName", sender != null ? sender.fullName() : null);
                } catch (Exception ignored) { item.put("senderName", null); }
                result.add(item);
            } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
        }
        return result;
    }

    @Transactional
    public void handleOwnershipTransferred(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null); if (saga == null || saga.getState() != SagaState.WAITING_TRANSFER) return;
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElse(null); if (transfer == null) return;
        transfer.setStatus(TransferStatus.COMPLETED); transfer.setTransferredAt(Instant.now()); transferRepository.save(transfer);
        saga.setState(SagaState.COMPLETED); saga.setStep("TRANSFER_COMPLETED"); sagaRepository.save(saga);
        outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_COMPLETED").topic("haloticket.marketplace.gift.completed.v1").payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    @Transactional
    public void requestRelease(String sagaId, String reason) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || saga.getState() == SagaState.COMPENSATED || saga.getState() == SagaState.COMPLETED) return;
        saga.setState(SagaState.COMPENSATING); saga.setStep("RELEASE_REQUESTED"); saga.setLastError(reason); saga.setNextRetryAt(Instant.now().plusSeconds(10)); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", saga.getTicketId());
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create release command", exception); }
    }

    @Transactional
    public void handleTicketReleased(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null); if (saga == null || saga.getState() != SagaState.COMPENSATING) return;
        listingRepository.findByTicketIdAndStatus(saga.getTicketId(), ListingStatus.ACTIVE).ifPresent(listing -> { listing.setStatus(ListingStatus.CANCELLED); listingRepository.save(listing); });
        transferRepository.findBySagaId(sagaId).ifPresent(transfer -> { transfer.setStatus(TransferStatus.CANCELLED); transfer.setCancellationReason(saga.getLastError()); transferRepository.save(transfer); });
        saga.setState(SagaState.COMPENSATED); saga.setStep("RELEASE_CONFIRMED"); saga.setNextRetryAt(null); sagaRepository.save(saga);
        boolean gift = "GIFT_TRANSFER".equals(saga.getType());
        boolean sellerCancelled = "SELLER_CANCELLED".equals(saga.getLastError());
        String eventType = gift ? "GIFT_CANCELLED" : sellerCancelled ? "RESALE_LISTING_CANCELLED" : "RESALE_LISTING_FAILED";
        String topic = gift ? "haloticket.marketplace.gift.cancelled.v1" : sellerCancelled ? "haloticket.marketplace.listing.cancelled.v1" : "haloticket.marketplace.listing.failed.v1";
        outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType(eventType).topic(topic).payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null; }
    private SagaResponse toResponse(MarketplaceSaga saga) { return new SagaResponse(saga.getId(), saga.getState(), saga.getBusinessId(), saga.getLastError()); }
    private ReservationResponse reservationResponse(MarketplaceTransaction transaction) {
        return new ReservationResponse(transaction.getId(), transaction.getTicketListing().getId(), transaction.getStatus(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getReservationExpiresAt());
    }
    private String hash(CreateListingRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hash(CreateGiftRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hashToken(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
