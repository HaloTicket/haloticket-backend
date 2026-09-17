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
        return listingRepository.findByStatusAndPublishedAtIsNotNullAndExpiresAtAfter(ListingStatus.ACTIVE, Instant.now(), pageable).map(ListingResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ListingResponse> myListings(String actorId, Pageable pageable) { return listingRepository.findBySellerId(actorId, pageable).map(ListingResponse::from); }

    @Transactional
    public void activateFromTicketLock(String sagaId) {
        MarketplaceSaga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null || saga.getState() != SagaState.WAITING_LOCK) return;
        try {
            var node = objectMapper.readTree(saga.getPayload());
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

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) { return node.hasNonNull(field) ? new BigDecimal(node.get(field).asText()) : null; }
    private SagaResponse toResponse(MarketplaceSaga saga) { return new SagaResponse(saga.getId(), saga.getState(), saga.getBusinessId(), saga.getLastError()); }
    private String hash(CreateListingRequest request) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
