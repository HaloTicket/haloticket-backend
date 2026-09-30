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
    private final EventTransferPolicyService policyGate;

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
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId());
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
                    ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId());
        }

        BigDecimal originalPrice = ticket.originalPrice() == null ? BigDecimal.ZERO : ticket.originalPrice();
        BigDecimal minPrice = originalPrice.multiply(policy.minPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.CEILING);
        BigDecimal maxPrice = originalPrice.multiply(policy.maxPricePercent())
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
        return new TicketServiceClient.ResaleEligibilityResponse(
                ticket.ticketId(), ticket.eventId(), true, null, originalPrice,
                minPrice, maxPrice, policy.version(), policy.closesAt(), policy.termsVersion(),
                ticket.ticketTypeName(), ticket.section(), ticket.row(), ticket.seat(), ticket.ticketTypeId(), ticket.showtimeId());
    }

    @Transactional
    public SagaResponse startResale(String actorId, String authorization, String idempotencyKey, CreateListingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        if (request == null || request.price() == null || request.price().scale() > 0 || request.price().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_INVALID");
        }
        List<String> ticketIds = selectedIds(request.ticketId(), request.ticketIds());
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

        List<TicketServiceClient.ResaleEligibilityResponse> eligibleTickets = new ArrayList<>();
        for (String ticketId : ticketIds) {
            TicketServiceClient.ResaleEligibilityResponse item;
            try { item = eligibility(authorization, ticketId); }
            catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ELIGIBILITY_SERVICE_UNAVAILABLE", exception); }
            if (item == null || !item.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
            if (!eligibleTickets.isEmpty() && (!Objects.equals(eligibleTickets.get(0).eventId(), item.eventId())
                    || !Objects.equals(eligibleTickets.get(0).showtimeId(), item.showtimeId())))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKETS_MUST_SHARE_SHOWTIME");
            if (item.eventId() != null) policyGate.requireResaleEnabled(item.eventId());
            if (request.price().compareTo(item.minPrice()) < 0 || request.price().compareTo(item.maxPrice()) > 0)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_OUT_OF_RANGE");
            if (request.policyVersion() != null && item.policyVersion() != null && !request.policyVersion().equals(item.policyVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED");
            eligibleTickets.add(item);
        }
        TicketServiceClient.ResaleEligibilityResponse eligibility = eligibleTickets.get(0);
        BigDecimal minPrice = eligibility.minPrice();
        BigDecimal maxPrice = eligibility.maxPrice();
        Instant now = Instant.now();
        String sagaId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId); payload.put("ticketId", ticketIds.get(0)); payload.put("ticketIds", ticketIds); payload.put("sellerId", actorId);
        payload.put("price", request.price()); payload.put("eventId", eligibility.eventId()); payload.put("originalPrice", eligibility.originalPrice());
        payload.put("minPrice", minPrice); payload.put("maxPrice", maxPrice); payload.put("policyVersion", eligibility.policyVersion());
        payload.put("closesAt", eligibility.closesAt());
        payload.put("termsVersion", request.termsVersion() != null ? request.termsVersion() : eligibility.termsVersion());
        payload.put("ticketTypeName", eligibility.ticketTypeName()); payload.put("section", eligibility.section());
        payload.put("row", eligibility.row()); payload.put("seat", eligibility.seat());
        payload.put("tickets", eligibleTickets.stream().map(item -> snapshot(item)).toList());
        int quantity = ticketIds.size();
        payload.put("quantity", quantity);
        payload.put("allowRetail", retailAllowed(quantity, request.allowRetail()));
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("RESALE_LISTING").ticketId(ticketIds.get(0)).actorId(actorId)
                    .state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED").payload(objectMapper.writeValueAsString(payload))
                    .idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED")
                    .topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(Map.of("messageId", UUID.randomUUID().toString(), "sagaId", sagaId, "ticketId", ticketIds.get(0), "ticketIds", ticketIds, "sellerId", actorId, "purpose", "RESALE")))
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
        return reserveListing(buyerId, idempotencyKey, listingId, null);
    }

    @Transactional
    public ReservationResponse reserveListing(String buyerId, String idempotencyKey, String listingId, List<String> requestedIds) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        Optional<MarketplaceTransaction> existing = transactionRepository.findByPaymentReference(idempotencyKey);
        if (existing.isPresent()) {
            MarketplaceTransaction transaction = existing.get();
            if (!transaction.getBuyerId().equals(buyerId) || !transaction.getTicketListing().getId().equals(listingId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different request");
            }
            if ((requestedIds == null && Boolean.TRUE.equals(transaction.getSelectionExplicit()))
                    || (requestedIds != null && !new LinkedHashSet<>(selectedIds(null, requestedIds)).equals(transaction.getTicketIds())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was reused with a different selection");
            return reservationResponse(transaction);
        }

        TicketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND"));
        Instant now = Instant.now();
        if (listing.getStatus() == ListingStatus.RESERVED && buyerId.equals(listing.getReservedBy())
                && listing.getReservedUntil() != null && listing.getReservedUntil().isAfter(now)) {
            MarketplaceTransaction transaction = transactionRepository.findFirstByTicketListing_IdAndBuyerIdAndStatusIn(
                    listingId, buyerId, List.of(MarketplaceTransactionStatus.PENDING_PAYMENT,
                            MarketplaceTransactionStatus.PAYMENT_PROCESSING))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_TRANSACTION_NOT_FOUND"));
            if ((requestedIds == null && Boolean.TRUE.equals(transaction.getSelectionExplicit()))
                    || (requestedIds != null && !new LinkedHashSet<>(selectedIds(null, requestedIds)).equals(transaction.getTicketIds())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SELECTION_NOT_AVAILABLE");
            return reservationResponse(transaction);
        }
        if (listing.getStatus() != ListingStatus.ACTIVE || listing.getPublishedAt() == null
                || (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_AVAILABLE");
        }
        if (listing.getEventId() != null) {
            policyGate.requireResaleEnabled(listing.getEventId());
        }
        if (listing.getSellerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BUYER_IS_SELLER");
        }
        List<String> remaining = listing.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList();
        List<String> selected = requestedIds == null ? remaining : selectedIds(null, requestedIds);
        if (!remaining.containsAll(selected) || (!Boolean.TRUE.equals(listing.getAllowRetail()) && selected.size() != remaining.size()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SELECTION_NOT_AVAILABLE");

        Instant reservationExpiresAt = now.plusSeconds(10 * 60);
        listing.setStatus(ListingStatus.RESERVED);
        listing.setReservedBy(buyerId);
        listing.setReservedUntil(reservationExpiresAt);
        listingRepository.save(listing);
        MarketplaceTransaction transaction = MarketplaceTransaction.builder()
                .ticketListing(listing).buyerId(buyerId).sellerId(listing.getSellerId())
                .amount(listing.getPrice().multiply(BigDecimal.valueOf(selected.size())))
                .ticketIds(new LinkedHashSet<>(selected))
                .selectionExplicit(requestedIds != null)
                .currency("VND").status(MarketplaceTransactionStatus.PENDING_PAYMENT)
                .paymentReference(idempotencyKey).reservationExpiresAt(reservationExpiresAt).build();
        return reservationResponse(transactionRepository.save(transaction));
    }

    private ListingStatus resolveStatusAfterReservation(TicketListing listing, Instant now) {
        if (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now)) {
            return ListingStatus.EXPIRED;
        }
        if (listing.getEventId() != null) {
            try {
                policyGate.requireResaleEnabled(listing.getEventId());
            } catch (Exception e) {
                listing.setPauseReason("ORGANIZER_DISABLED_RESALE");
                return ListingStatus.PAUSED_BY_EVENT;
            }
        }
        listing.setPauseReason(null);
        return ListingStatus.ACTIVE;
    }

    @Transactional
    public void expireReservations() {
        Instant now = Instant.now();
        for (TicketListing candidate : listingRepository.findByStatusAndReservedUntilBefore(ListingStatus.RESERVED, now)) {
            TicketListing listing = listingRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (listing == null || listing.getStatus() != ListingStatus.RESERVED || listing.getReservedUntil() == null
                    || !listing.getReservedUntil().isBefore(now)) continue;
            MarketplaceTransaction transaction = transactionRepository.findByTicketListing_IdAndStatus(listing.getId(), MarketplaceTransactionStatus.PENDING_PAYMENT).orElse(null);
            if (transaction == null) continue;
            transaction.setStatus(MarketplaceTransactionStatus.EXPIRED);
            transaction.setCancelledAt(now);
            transactionRepository.save(transaction);
            listing.setStatus(resolveStatusAfterReservation(listing, now));
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
            listing.setStatus(resolveStatusAfterReservation(listing, now));
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
        if (listing.getStatus() != ListingStatus.ACTIVE && listing.getStatus() != ListingStatus.PAUSED_BY_EVENT) throw new ResponseStatusException(HttpStatus.CONFLICT, "LISTING_NOT_CANCELLABLE");

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
        command.put("ticketIds", listing.remainingMembers().stream().map(TicketMemberSnapshot::getTicketId).toList());
        command.put("lockTicketIds", listing.getMembers().isEmpty() ? List.of(listing.getTicketId())
                : listing.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList());
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
                invitation.put("ticketIds", memberIds(transfer));
                invitation.put("quantity", memberIds(transfer).size());
                invitation.put("tickets", transfer.getMembers());
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
            String eventId = text(node, "eventId");
            boolean resaleAllowed = true;
            if (eventId != null) {
                try { policyGate.requireResaleEnabled(eventId); } catch (Exception e) { resaleAllowed = false; }
            }
            ListingStatus initialStatus = resaleAllowed ? ListingStatus.ACTIVE : ListingStatus.PAUSED_BY_EVENT;
            String pauseReason = resaleAllowed ? null : "ORGANIZER_DISABLED_RESALE";
            TicketListing listing = TicketListing.builder().ticketId(node.get("ticketId").asText()).sellerId(node.get("sellerId").asText())
                    .price(new BigDecimal(node.get("price").asText())).originalPrice(decimal(node,"originalPrice")).minPriceSnapshot(decimal(node,"minPrice"))
                    .maxPriceSnapshot(decimal(node,"maxPrice")).eventId(eventId).status(initialStatus).pauseReason(pauseReason).expiresAt(expiry)
                    .ticketTypeNameSnapshot(text(node,"ticketTypeName")).sectionSnapshot(text(node,"section"))
                    .rowSnapshot(text(node,"row")).seatSnapshot(text(node,"seat"))
                    .quantity(node.path("quantity").asInt(1))
                    .members(members(node))
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
        if (request == null || request.recipientEmail() == null || request.recipientEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "RECIPIENT_REQUIRED");
        }
        List<String> ticketIds = selectedIds(request.ticketId(), request.ticketIds());
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
        List<TicketServiceClient.ResaleEligibilityResponse> eligibleTickets = new ArrayList<>();
        for (String ticketId : ticketIds) {
            TicketServiceClient.ResaleEligibilityResponse item;
            try { item = ticketServiceClient.getResaleEligibility(authorization, ticketId); }
            catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_SERVICE_UNAVAILABLE", exception); }
            if (item == null || !item.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
            if (!eligibleTickets.isEmpty() && (!Objects.equals(eligibleTickets.get(0).eventId(), item.eventId())
                    || !Objects.equals(eligibleTickets.get(0).showtimeId(), item.showtimeId())))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKETS_MUST_SHARE_SHOWTIME");
            eligibleTickets.add(item);
        }
        TicketServiceClient.ResaleEligibilityResponse eligibility = eligibleTickets.get(0);
        if (eligibility.eventId() != null) policyGate.requireGiftEnabled(eligibility.eventId());
        String sagaId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sagaId", sagaId);
        payload.put("ticketId", ticketIds.get(0));
        payload.put("ticketIds", ticketIds);
        payload.put("tickets", eligibleTickets.stream().map(item -> snapshot(item)).toList());
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
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("GIFT_TRANSFER").ticketId(ticketIds.get(0)).actorId(actorId).state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED")
                    .payload(objectMapper.writeValueAsString(payload)).idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            TicketTransfer transfer = TicketTransfer.builder().ticketId(ticketIds.get(0)).members(eligibleTickets.stream().map(item -> snapshot(item)).toList()).fromUserId(actorId).toUserId(recipient.userId()).recipientEmail(recipientEmail).message(request.message()).type(TransferType.GIFT).status(TransferStatus.PENDING).sagaId(sagaId).eventId(eligibility.eventId()).ticketTypeName(eligibility.ticketTypeName()).build();
            transferRepository.save(transfer); saga.setBusinessId(transfer.getId()); sagaRepository.save(saga);
            Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", ticketIds.get(0)); command.put("ticketIds", ticketIds); command.put("sellerId", actorId); command.put("purpose", "GIFT");
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
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", transfer.getTicketId()); command.put("ticketIds", memberIds(transfer)); command.put("lockTicketIds", memberIds(transfer)); command.put("fromUserId", transfer.getFromUserId()); command.put("toUserId", actorId); command.put("lockOperationId", sagaId); command.put("purpose", "GIFT");
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
            result.put("ticketIds", memberIds(transfer));
            result.put("quantity", memberIds(transfer).size());
            result.put("tickets", transfer.getMembers());
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
                item.put("ticketIds", memberIds(transfer));
                item.put("quantity", memberIds(transfer).size());
                item.put("tickets", transfer.getMembers());
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
        if (saga == null || "RESALE_PURCHASE".equals(saga.getType())
                || saga.getState() == SagaState.COMPENSATED || saga.getState() == SagaState.COMPLETED) return;
        saga.setState(SagaState.COMPENSATING); saga.setStep("RELEASE_REQUESTED"); saga.setLastError(reason); saga.setNextRetryAt(Instant.now().plusSeconds(10)); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", saga.getTicketId()); command.put("ticketIds", sagaTicketIds(saga));
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_RELEASE_REQUESTED").topic("haloticket.ticket.release.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create release command", exception); }
    }

    @Transactional
    public void handleTicketReleased(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || "RESALE_PURCHASE".equals(saga.getType()) || saga.getState() != SagaState.COMPENSATING) return;
        if ("RESALE_LISTING".equals(saga.getType()) && saga.getBusinessId() != null) {
            listingRepository.findById(saga.getBusinessId()).ifPresent(listing -> {
                if (listing.getStatus() == ListingStatus.ACTIVE || listing.getStatus() == ListingStatus.PAUSED_BY_EVENT) {
                    listing.setStatus(ListingStatus.CANCELLED);
                    listingRepository.save(listing);
                }
            });
        }
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
        List<String> ids = transaction.getTicketIds() == null || transaction.getTicketIds().isEmpty()
                ? List.of(transaction.getTicketListing().getTicketId()) : List.copyOf(transaction.getTicketIds());
        return new ReservationResponse(transaction.getId(), transaction.getTicketListing().getId(), transaction.getStatus(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getReservationExpiresAt(), ids.size(), ids);
    }

    @Transactional
    public void paymentFailed(String transactionId) {
        MarketplaceTransaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null || (transaction.getStatus() != MarketplaceTransactionStatus.PENDING_PAYMENT
                && transaction.getStatus() != MarketplaceTransactionStatus.PAYMENT_PROCESSING)) return;
        TicketListing listing = listingRepository.findByIdForUpdate(transaction.getTicketListing().getId()).orElse(null);
        if (listing == null || listing.getStatus() != ListingStatus.RESERVED
                || !transaction.getBuyerId().equals(listing.getReservedBy())) return;
        transaction.setStatus(MarketplaceTransactionStatus.CANCELLED);
        transaction.setCancelledAt(Instant.now());
        transactionRepository.save(transaction);
        listing.setStatus(resolveStatusAfterReservation(listing, Instant.now()));
        listing.setReservedBy(null); listing.setReservedUntil(null);
        listingRepository.save(listing);
    }
    private static List<String> selectedIds(String legacyId, List<String> ids) {
        List<String> selected = ids == null ? (legacyId == null ? List.of() : List.of(legacyId)) : ids;
        if (selected.isEmpty() || selected.size() > 50 || selected.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(selected).size() != selected.size()
                || legacyId != null && !legacyId.isBlank() && ids != null && !ids.contains(legacyId))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKET_IDS_INVALID");
        return List.copyOf(selected);
    }
    private static TicketMemberSnapshot snapshot(TicketServiceClient.ResaleEligibilityResponse item) {
        return new TicketMemberSnapshot(item.ticketId(), item.ticketTypeId(), item.showtimeId(),
                item.ticketTypeName(), item.section(), item.row(), item.seat(), item.originalPrice(), false);
    }
    private List<TicketMemberSnapshot> members(com.fasterxml.jackson.databind.JsonNode node) {
        var tickets = node.path("tickets");
        if (!tickets.isArray()) return List.of(new TicketMemberSnapshot(text(node,"ticketId"), null, null,
                text(node,"ticketTypeName"), text(node,"section"), text(node,"row"), text(node,"seat"), decimal(node,"originalPrice"), false));
        List<TicketMemberSnapshot> result = new ArrayList<>();
        for (var ticket : tickets) result.add(new TicketMemberSnapshot(text(ticket,"ticketId"), text(ticket,"ticketTypeId"),
                text(ticket,"showtimeId"), text(ticket,"ticketTypeName"), text(ticket,"section"), text(ticket,"row"),
                text(ticket,"seat"), decimal(ticket,"originalPrice"), false));
        return result;
    }
    private static List<String> memberIds(TicketTransfer transfer) {
        return transfer.getMembers() == null || transfer.getMembers().isEmpty() ? List.of(transfer.getTicketId())
                : transfer.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList();
    }
    private List<String> sagaTicketIds(MarketplaceSaga saga) {
        try {
            var ids = objectMapper.readTree(saga.getPayload()).path("ticketIds");
            if (!ids.isArray()) return List.of(saga.getTicketId());
            List<String> result = new ArrayList<>();
            ids.forEach(id -> result.add(id.asText()));
            return result;
        } catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private String hash(CreateListingRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hash(CreateGiftRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hashToken(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
