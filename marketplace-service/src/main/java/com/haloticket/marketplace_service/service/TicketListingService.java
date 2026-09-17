package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.dto.*;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import lombok.RequiredArgsConstructor;
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
    private final TicketServiceClient ticketServiceClient;
    private final ObjectMapper objectMapper;

    public TicketServiceClient.ResaleEligibilityResponse eligibility(String authorization, String ticketId) {
        return ticketServiceClient.getResaleEligibility(authorization, ticketId);
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

        TicketServiceClient.ResaleEligibilityResponse eligibility;
        try { eligibility = ticketServiceClient.getResaleEligibility(authorization, request.ticketId()); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_SERVICE_UNAVAILABLE", exception); }
        if (eligibility == null || !eligibility.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
        if (eligibility.minPrice() != null && request.price().compareTo(eligibility.minPrice()) < 0 || eligibility.maxPrice() != null && request.price().compareTo(eligibility.maxPrice()) > 0) {
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
        payload.put("minPrice", eligibility.minPrice()); payload.put("maxPrice", eligibility.maxPrice()); payload.put("policyVersion", eligibility.policyVersion());
        payload.put("closesAt", eligibility.closesAt());
        payload.put("termsVersion", request.termsVersion() != null ? request.termsVersion() : eligibility.termsVersion());
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
        return listingRepository.findPublicActive(ListingStatus.ACTIVE, Instant.now(), pageable).map(ListingResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> myListings(String actorId, Pageable pageable) { return listingRepository.findBySellerId(actorId, pageable).map(ListingResponse::from); }

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
                saga.setState(SagaState.WAITING_RECIPIENT); saga.setStep("INVITATION_CREATED"); sagaRepository.save(saga);
                Map<String, Object> invitation = new LinkedHashMap<>();
                invitation.put("sagaId", sagaId); invitation.put("ticketId", transfer.getTicketId()); invitation.put("recipientEmail", transfer.getRecipientEmail());
                invitation.put("message", transfer.getMessage()); invitation.put("invitationToken", token); invitation.put("expiresAt", transfer.getInvitationExpiresAt());
                outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_INVITATION_CREATED")
                        .topic("haloticket.marketplace.gift.invited.v1").payload(objectMapper.writeValueAsString(invitation)).status(OutboxStatus.PENDING).retryCount(0).build());
                return;
            }
            Instant expiry = node.hasNonNull("closesAt") ? Instant.parse(node.get("closesAt").asText()) : saga.getDeadlineAt();
            TicketListing listing = TicketListing.builder().ticketId(node.get("ticketId").asText()).sellerId(node.get("sellerId").asText())
                    .price(new BigDecimal(node.get("price").asText())).originalPrice(decimal(node,"originalPrice")).minPriceSnapshot(decimal(node,"minPrice"))
                    .maxPriceSnapshot(decimal(node,"maxPrice")).eventId(text(node,"eventId")).status(ListingStatus.ACTIVE).expiresAt(expiry)
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
        TicketServiceClient.ResaleEligibilityResponse eligibility;
        try { eligibility = ticketServiceClient.getResaleEligibility(authorization, request.ticketId()); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_SERVICE_UNAVAILABLE", exception); }
        if (eligibility == null || !eligibility.eligible()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_ELIGIBLE");
        String sagaId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("sagaId", sagaId); payload.put("ticketId", request.ticketId()); payload.put("fromUserId", actorId); payload.put("recipientEmail", request.recipientEmail()); payload.put("message", request.message());
        try {
            MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("GIFT_TRANSFER").ticketId(request.ticketId()).actorId(actorId).state(SagaState.WAITING_LOCK).step("LOCK_REQUESTED")
                    .payload(objectMapper.writeValueAsString(payload)).idempotencyKey(idempotencyKey).requestHash(hash).deadlineAt(eligibility.closesAt()).nextRetryAt(now).retryCount(0).build();
            sagaRepository.save(saga);
            TicketTransfer transfer = TicketTransfer.builder().ticketId(request.ticketId()).fromUserId(actorId).recipientEmail(request.recipientEmail()).message(request.message()).type(TransferType.GIFT).status(TransferStatus.PENDING).sagaId(sagaId).build();
            transferRepository.save(transfer); saga.setBusinessId(transfer.getId()); sagaRepository.save(saga);
            Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId); command.put("ticketId", request.ticketId()); command.put("sellerId", actorId); command.put("purpose", "GIFT");
            outboxRepository.save(OutboxEvent.builder().aggregateType("MARKETPLACE_SAGA").aggregateId(sagaId).eventType("TICKET_LOCK_REQUESTED").topic("haloticket.ticket.lock.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
            return toResponse(saga);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create gift saga", exception); }
    }

    @Transactional
    public SagaResponse acceptGift(String actorId, String sagaId, String token) {
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (saga.getState() != SagaState.WAITING_RECIPIENT || token == null || transfer.getInvitationExpiresAt() == null || transfer.getInvitationExpiresAt().isBefore(Instant.now()) || !hashToken(token).equals(transfer.getInvitationTokenHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GIFT_INVITATION_INVALID");
        }
        transfer.setToUserId(actorId); transferRepository.save(transfer); saga.setState(SagaState.WAITING_TRANSFER); saga.setStep("TRANSFER_REQUESTED"); sagaRepository.save(saga);
        Map<String, Object> command = new LinkedHashMap<>(); command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId); command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", transfer.getTicketId()); command.put("fromUserId", transfer.getFromUserId()); command.put("toUserId", actorId); command.put("lockOperationId", sagaId);
        try {
            outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("TICKET_OWNERSHIP_TRANSFER_REQUESTED").topic("haloticket.ticket.ownership.transfer.requested.v1").payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Could not create transfer command", exception); }
        return toResponse(saga);
    }

    @Transactional
    public void handleOwnershipTransferred(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null); if (saga == null || saga.getState() != SagaState.WAITING_TRANSFER) return;
        TicketTransfer transfer = transferRepository.findBySagaId(sagaId).orElse(null); if (transfer == null) return;
        transfer.setStatus(TransferStatus.COMPLETED); transfer.setTransferredAt(Instant.now()); transferRepository.save(transfer);
        saga.setState(SagaState.COMPLETED); saga.setStep("TRANSFER_COMPLETED"); sagaRepository.save(saga);
        outboxRepository.save(OutboxEvent.builder().aggregateType("TICKET_TRANSFER").aggregateId(transfer.getId()).eventType("GIFT_COMPLETED").topic("haloticket.marketplace.gift.completed.v1").payload(saga.getPayload()).status(OutboxStatus.PENDING).retryCount(0).build());
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null; }
    private SagaResponse toResponse(MarketplaceSaga saga) { return new SagaResponse(saga.getId(), saga.getState(), saga.getBusinessId(), saga.getLastError()); }
    private String hash(CreateListingRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hash(CreateGiftRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String hashToken(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
