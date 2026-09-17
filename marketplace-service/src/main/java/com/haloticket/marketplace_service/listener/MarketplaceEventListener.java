package com.haloticket.marketplace_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.entity.ProcessedEvent;
import com.haloticket.marketplace_service.repository.ProcessedEventRepository;
import com.haloticket.marketplace_service.service.TicketListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class MarketplaceEventListener {
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEvents;
    private final TicketListingService listingService;
    private final com.haloticket.marketplace_service.service.ResalePurchaseService purchaseService;

    @KafkaListener(topics = "haloticket.ticket.locked.v1", groupId = "marketplace-service")
    @Transactional
    public void onTicketLocked(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageId = root.path("messageId").asText(root.path("id").asText());
            if (messageId.isBlank() || processedEvents.existsByConsumerNameAndMessageId("marketplace-ticket-lock", messageId)) return;
            JsonNode payload = root.has("payload") && root.get("payload").isObject() ? root.get("payload") : root;
            String sagaId = payload.path("sagaId").asText();
            processedEvents.save(ProcessedEvent.builder().consumerName("marketplace-ticket-lock").messageId(messageId).processedAt(Instant.now()).build());
            if (!sagaId.isBlank()) listingService.handleTicketLockResult(sagaId, "LOCKED".equals(root.path("state").asText()));
        } catch (Exception exception) { throw new IllegalStateException("Failed to process TICKET_LOCKED", exception); }
    }

    @KafkaListener(topics = "haloticket.ticket.ownership.transferred.v1", groupId = "marketplace-service")
    @Transactional
    public void onOwnershipTransferred(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageId = root.path("messageId").asText(root.path("id").asText());
            if (messageId.isBlank() || processedEvents.existsByConsumerNameAndMessageId("marketplace-ticket-transfer", messageId)) return;
            String sagaId = root.path("sagaId").asText();
            processedEvents.save(ProcessedEvent.builder().consumerName("marketplace-ticket-transfer").messageId(messageId).processedAt(Instant.now()).build());
            if (!sagaId.isBlank()) {
                if ("TRANSFERRED".equals(root.path("state").asText())) {
                    purchaseService.handleOwnershipTransferred(sagaId);
                    listingService.handleOwnershipTransferred(sagaId);
                } else {
                    purchaseService.handleOwnershipTransferFailed(sagaId, "TICKET_TRANSFER_REJECTED");
                }
            }
        } catch (Exception exception) { throw new IllegalStateException("Failed to process TICKET_OWNERSHIP_TRANSFERRED", exception); }
    }

    @KafkaListener(topics = "haloticket.payment.completed.v1", groupId = "marketplace-service")
    @Transactional
    public void onPaymentCompleted(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageId = root.path("messageId").asText(root.path("paymentId").asText());
            if (messageId.isBlank() || processedEvents.existsByConsumerNameAndMessageId("marketplace-payment", messageId)) return;
            String transactionId = root.path("bookingId").asText();
            java.math.BigDecimal amount = root.hasNonNull("amount") ? root.get("amount").decimalValue() : null;
            purchaseService.handlePaymentCompleted(transactionId, root.path("paymentId").asText(), amount);
            processedEvents.save(ProcessedEvent.builder().consumerName("marketplace-payment").messageId(messageId).processedAt(Instant.now()).build());
        } catch (Exception exception) { throw new IllegalStateException("Failed to process resale payment completion", exception); }
    }

    @KafkaListener(topics = "haloticket.ticket.released.v1", groupId = "marketplace-service")
    @Transactional
    public void onTicketReleased(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageId = root.path("messageId").asText(root.path("id").asText());
            if (messageId.isBlank() || processedEvents.existsByConsumerNameAndMessageId("marketplace-ticket-release", messageId)) return;
            String sagaId = root.path("sagaId").asText();
            processedEvents.save(ProcessedEvent.builder().consumerName("marketplace-ticket-release").messageId(messageId).processedAt(Instant.now()).build());
            if (!sagaId.isBlank()) listingService.handleTicketReleased(sagaId);
        } catch (Exception exception) { throw new IllegalStateException("Failed to process TICKET_RELEASED", exception); }
    }
}
