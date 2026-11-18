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

/**
 * Lắng nghe sự kiện Kafka từ {@code ticket-service} và {@code payment-service} để đẩy saga chuyển nhượng
 * và giao dịch mua vé bán lại sang bước tiếp theo. Mỗi thông điệp được ghi nhận trong {@code ProcessedEvent} để không xử lý trùng.
 */
@Component
@RequiredArgsConstructor
public class MarketplaceEventListener {
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEvents;
    private final TicketListingService listingService;
    private final com.haloticket.marketplace_service.service.ResalePurchaseService purchaseService;

    /**
     * Xử lý kết quả khóa vé: báo cho service để saga tiếp tục (đã khóa) hoặc thất bại.
     *
     * @param message nội dung JSON của sự kiện khóa vé
     * @throws IllegalStateException khi xử lý lỗi, để Kafka thử lại
     */
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

    /**
     * Xử lý kết quả chuyển quyền sở hữu vé: thành công thì hoàn tất giao dịch mua và saga,
     * ngược lại báo giao dịch mua lỗi với mã {@code TICKET_TRANSFER_REJECTED}.
     *
     * @param message nội dung JSON của sự kiện chuyển quyền sở hữu
     * @throws IllegalStateException khi xử lý lỗi, để Kafka thử lại
     */
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

    /**
     * Xử lý thanh toán thành công của giao dịch mua vé bán lại (mã giao dịch lấy từ {@code bookingId}).
     *
     * @param message nội dung JSON của sự kiện thanh toán hoàn tất
     * @throws IllegalStateException khi xử lý lỗi, để Kafka thử lại
     */
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

    /**
     * Xử lý thanh toán thất bại của giao dịch mua vé bán lại (mã giao dịch lấy từ {@code bookingId}).
     *
     * @param message nội dung JSON của sự kiện thanh toán thất bại
     * @throws IllegalStateException khi xử lý lỗi, để Kafka thử lại
     */
    @KafkaListener(topics = "haloticket.payment.failed.v1", groupId = "marketplace-service")
    @Transactional
    public void onPaymentFailed(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String messageId = root.path("messageId").asText(root.path("paymentId").asText());
            if (messageId.isBlank() || processedEvents.existsByConsumerNameAndMessageId("marketplace-payment-failed", messageId)) return;
            String transactionId = root.path("bookingId").asText();
            if (!transactionId.isBlank()) listingService.paymentFailed(transactionId);
            processedEvents.save(ProcessedEvent.builder().consumerName("marketplace-payment-failed")
                    .messageId(messageId).processedAt(Instant.now()).build());
        } catch (Exception exception) { throw new IllegalStateException("Failed to process resale payment failure", exception); }
    }

    /**
     * Xử lý sự kiện vé đã được nhả khóa để saga hoàn tất bước bù trừ.
     *
     * @param message nội dung JSON của sự kiện nhả vé
     * @throws IllegalStateException khi xử lý lỗi, để Kafka thử lại
     */
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
