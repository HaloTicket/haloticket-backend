package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.dto.PaymentInitiationResponse;
import com.haloticket.marketplace_service.dto.ResalePurchaseStatusResponse;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ResalePurchaseService {
    private final MarketplaceTransactionRepository transactions;
    private final MarketplaceSagaRepository sagas;
    private final TicketTransferRepository transfers;
    private final SellerSettlementRepository settlements;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ResalePurchaseStatusResponse getStatus(String buyerId, String transactionId) {
        MarketplaceTransaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND"));
        if (!transaction.getBuyerId().equals(buyerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new ResalePurchaseStatusResponse(transaction.getId(), transaction.getStatus(), transaction.getSagaId());
    }

    @Transactional
    public PaymentInitiationResponse startPayment(String buyerId, String transactionId) {
        MarketplaceTransaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND"));
        if (!transaction.getBuyerId().equals(buyerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if ((transaction.getStatus() != MarketplaceTransactionStatus.PENDING_PAYMENT
                && transaction.getStatus() != MarketplaceTransactionStatus.PAYMENT_PROCESSING)
                || transaction.getReservationExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_NOT_PAYABLE");
        }
        if (transaction.getStatus() == MarketplaceTransactionStatus.PENDING_PAYMENT) {
            transaction.setStatus(MarketplaceTransactionStatus.PAYMENT_PROCESSING);
            transactions.save(transaction);
        }
        return new PaymentInitiationResponse(transaction.getId(), transaction.getAmount(), transaction.getCurrency(),
                transaction.getReservationExpiresAt());
    }

    @Transactional
    public void handlePaymentCompleted(String transactionId, String paymentId, BigDecimal paidAmount) {
        MarketplaceTransaction transaction = transactions.findById(transactionId).orElse(null);
        if (transaction == null || transaction.getStatus() == MarketplaceTransactionStatus.TRANSFERRING
                || transaction.getStatus() == MarketplaceTransactionStatus.COMPLETED) return;
        if (transaction.getStatus() != MarketplaceTransactionStatus.PAYMENT_PROCESSING
                || paidAmount == null || paidAmount.compareTo(transaction.getAmount()) != 0) {
            throw new IllegalStateException("Payment does not match payable resale transaction");
        }
        TicketListing listing = transaction.getTicketListing();
        String sagaId = UUID.randomUUID().toString();
        MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("RESALE_PURCHASE")
                .ticketId(listing.getTicketId()).actorId(transaction.getBuyerId()).businessId(transaction.getId())
                .state(SagaState.WAITING_TRANSFER).step("TRANSFER_REQUESTED")
                .payload("{}").idempotencyKey("purchase:" + transaction.getId()).requestHash(transaction.getId())
                .deadlineAt(transaction.getReservationExpiresAt()).nextRetryAt(Instant.now()).retryCount(0).build();
        sagas.save(saga);
        TicketTransfer transfer = TicketTransfer.builder().transaction(transaction).ticketId(listing.getTicketId())
                .fromUserId(listing.getSellerId()).toUserId(transaction.getBuyerId()).type(TransferType.RESALE)
                .status(TransferStatus.PENDING).sagaId(sagaId).build();
        transfers.save(transfer);
        transaction.setPaymentId(paymentId);
        transaction.setSagaId(sagaId);
        transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        transactions.save(transaction);
        try {
            Map<String,Object> command = new LinkedHashMap<>();
            command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId);
            command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", listing.getTicketId());
            command.put("fromUserId", listing.getSellerId()); command.put("toUserId", transaction.getBuyerId());
            command.put("lockOperationId", listing.getSagaId()); command.put("purpose", "RESALE");
            command.put("purchasePrice", transaction.getAmount()); command.put("acquisitionType", "RESALE");
            command.put("purchaseReference", "RSL-" + transaction.getId());
            outbox.save(OutboxEvent.builder().aggregateType("MARKETPLACE_TRANSACTION").aggregateId(transaction.getId())
                    .eventType("TICKET_OWNERSHIP_TRANSFER_REQUESTED").topic("haloticket.ticket.ownership.transfer.requested.v1")
                    .payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not request resale ownership transfer", exception); }
    }

    @Transactional
    public void handleOwnershipTransferred(String sagaId) {
        MarketplaceTransaction transaction = transactions.findBySagaId(sagaId).orElse(null);
        if (transaction == null || transaction.getStatus() == MarketplaceTransactionStatus.COMPLETED) return;
        MarketplaceSaga saga = sagas.findById(sagaId).orElseThrow();
        TicketTransfer transfer = transfers.findBySagaId(sagaId).orElseThrow();
        Instant now = Instant.now();
        transfer.setStatus(TransferStatus.COMPLETED); transfer.setTransferredAt(now); transfers.save(transfer);
        TicketListing listing = transaction.getTicketListing();
        listing.setStatus(ListingStatus.SOLD); listing.setSoldAt(now); listing.setReservedBy(null); listing.setReservedUntil(null);
        transaction.setStatus(MarketplaceTransactionStatus.COMPLETED); transaction.setCompletedAt(now); transactions.save(transaction);
        saga.setState(SagaState.COMPLETED); saga.setStep("PURCHASE_COMPLETED"); sagas.save(saga);
        settlements.save(SellerSettlement.builder().marketplaceTransaction(transaction).sellerId(transaction.getSellerId())
                .grossAmount(transaction.getAmount()).platformFee(BigDecimal.ZERO).netAmount(transaction.getAmount())
                .currency(transaction.getCurrency()).status(SettlementStatus.PENDING_SETTLEMENT).eligibleAt(now).build());
    }

    @Transactional
    public void handleOwnershipTransferFailed(String sagaId, String reason) {
        MarketplaceTransaction transaction = transactions.findBySagaId(sagaId).orElse(null);
        if (transaction == null || transaction.getStatus() != MarketplaceTransactionStatus.TRANSFERRING) return;
        MarketplaceSaga saga = sagas.findById(sagaId).orElseThrow();
        transaction.setStatus(MarketplaceTransactionStatus.REFUND_PENDING);
        transactions.save(transaction);
        saga.setState(SagaState.COMPENSATING); saga.setStep("REFUND_REQUESTED"); saga.setLastError(reason); sagas.save(saga);
        try {
            outbox.save(OutboxEvent.builder().aggregateType("MARKETPLACE_TRANSACTION").aggregateId(transaction.getId())
                    .eventType("RESALE_REFUND_REQUESTED").topic("haloticket.payment.refund.requested.v1")
                    .payload(objectMapper.writeValueAsString(Map.of("messageId", UUID.randomUUID().toString(),
                            "paymentId", transaction.getPaymentId(), "bookingId", transaction.getId(),
                            "amount", transaction.getAmount(), "reason", reason)))
                    .status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not request resale refund", exception); }
    }
}
