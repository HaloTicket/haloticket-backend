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
import java.math.RoundingMode;
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
    private final EventTransferPolicyService policyGate;

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
        List<String> selectedIds = transaction.getTicketIds() == null || transaction.getTicketIds().isEmpty()
                ? List.of(listing.getTicketId()) : List.copyOf(transaction.getTicketIds());
        MarketplaceSaga saga = MarketplaceSaga.builder().id(sagaId).type("RESALE_PURCHASE")
                .ticketId(selectedIds.get(0)).actorId(transaction.getBuyerId()).businessId(transaction.getId())
                .state(SagaState.WAITING_TRANSFER).step("TRANSFER_REQUESTED")
                .payload("{}").idempotencyKey("purchase:" + transaction.getId()).requestHash(transaction.getId())
                .deadlineAt(transaction.getReservationExpiresAt()).nextRetryAt(Instant.now().plusSeconds(30)).retryCount(0).build();
        sagas.save(saga);
        TicketTransfer transfer = TicketTransfer.builder().transaction(transaction).ticketId(selectedIds.get(0))
                .members(listing.getMembers().stream().filter(member -> selectedIds.contains(member.getTicketId())).toList())
                .fromUserId(listing.getSellerId()).toUserId(transaction.getBuyerId()).type(TransferType.RESALE)
                .status(TransferStatus.PENDING).sagaId(sagaId).build();
        transfers.save(transfer);
        transaction.setPaymentId(paymentId);
        transaction.setSagaId(sagaId);
        transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        transactions.save(transaction);
        enqueueTransfer(transaction, sagaId);
    }

    @Transactional
    public void retryTimedOutTransfer(String sagaId) {
        MarketplaceSaga saga = sagas.findById(sagaId).orElse(null);
        MarketplaceTransaction transaction = transactions.findBySagaId(sagaId).orElse(null);
        if (saga == null || !"RESALE_PURCHASE".equals(saga.getType()) || saga.getState() != SagaState.WAITING_TRANSFER
                || transaction == null || transaction.getStatus() != MarketplaceTransactionStatus.TRANSFERRING) return;
        enqueueTransfer(transaction, sagaId);
        saga.setStep("TRANSFER_RETRIED");
        saga.setNextRetryAt(Instant.now().plusSeconds(30));
        saga.setRetryCount((saga.getRetryCount() == null ? 0 : saga.getRetryCount()) + 1);
        sagas.save(saga);
    }

    private void enqueueTransfer(MarketplaceTransaction transaction, String sagaId) {
        TicketListing listing = transaction.getTicketListing();
        List<String> selectedIds = transaction.getTicketIds() == null || transaction.getTicketIds().isEmpty()
                ? List.of(listing.getTicketId()) : List.copyOf(transaction.getTicketIds());
        try {
            Map<String,Object> command = new LinkedHashMap<>();
            command.put("messageId", UUID.randomUUID().toString()); command.put("sagaId", sagaId);
            command.put("operationId", sagaId + ":TRANSFER"); command.put("ticketId", selectedIds.get(0)); command.put("ticketIds", selectedIds);
            command.put("fromUserId", listing.getSellerId()); command.put("toUserId", transaction.getBuyerId());
            command.put("lockOperationId", listing.getSagaId()); command.put("purpose", "RESALE");
            command.put("lockTicketIds", listing.getMembers().isEmpty() ? List.of(listing.getTicketId())
                    : listing.getMembers().stream().map(TicketMemberSnapshot::getTicketId).toList());
            command.put("purchasePrice", listing.getPrice()); command.put("acquisitionType", "RESALE");
            command.put("purchaseReference", "RSL-" + transaction.getId());
            outbox.save(OutboxEvent.builder().aggregateType("MARKETPLACE_TRANSACTION").aggregateId(transaction.getId())
                    .eventType("TICKET_OWNERSHIP_TRANSFER_REQUESTED").topic("haloticket.ticket.ownership.transfer.requested.v1")
                    .payload(objectMapper.writeValueAsString(command)).status(OutboxStatus.PENDING).retryCount(0).build());
        } catch (Exception exception) { throw new IllegalStateException("Could not request resale ownership transfer", exception); }
    }

    @Transactional
    public void handleOwnershipTransferred(String sagaId) {
        MarketplaceTransaction transaction = transactions.findBySagaId(sagaId).orElse(null);
        if (transaction == null || transaction.getStatus() != MarketplaceTransactionStatus.TRANSFERRING) return;
        MarketplaceSaga saga = sagas.findById(sagaId).orElseThrow();
        TicketTransfer transfer = transfers.findBySagaId(sagaId).orElseThrow();
        Instant now = Instant.now();
        transfer.setStatus(TransferStatus.COMPLETED); transfer.setTransferredAt(now); transfers.save(transfer);
        TicketListing listing = transaction.getTicketListing();
        Set<String> soldIds = transaction.getTicketIds() == null || transaction.getTicketIds().isEmpty()
                ? Set.of(listing.getTicketId()) : transaction.getTicketIds();
        listing.getMembers().stream().filter(member -> soldIds.contains(member.getTicketId())).forEach(member -> member.setSold(true));
        boolean allSold = listing.getMembers().isEmpty() || listing.remainingMembers().isEmpty();
        if (allSold) {
            listing.setStatus(ListingStatus.SOLD); listing.setSoldAt(now);
        } else if (listing.getExpiresAt() != null && !listing.getExpiresAt().isAfter(now)) {
            listing.setStatus(ListingStatus.EXPIRED);
        } else {
            listing.setStatus(ListingStatus.ACTIVE);
            listing.setPauseReason(null);
            if (listing.getEventId() != null) {
                try { policyGate.requireResaleEnabled(listing.getEventId()); }
                catch (Exception ignored) { listing.setStatus(ListingStatus.PAUSED_BY_EVENT); listing.setPauseReason("ORGANIZER_DISABLED_RESALE"); }
            }
        }
        listing.setQuantity(listing.remainingMembers().size());
        listing.setReservedBy(null); listing.setReservedUntil(null);
        transaction.setStatus(MarketplaceTransactionStatus.COMPLETED); transaction.setCompletedAt(now); transactions.save(transaction);
        saga.setState(SagaState.COMPLETED); saga.setStep("PURCHASE_COMPLETED"); sagas.save(saga);
        BigDecimal feePercent = transaction.getServiceFeePercent() == null ? new BigDecimal("5.00") : transaction.getServiceFeePercent();
        BigDecimal platformFee = transaction.getAmount().multiply(feePercent).movePointLeft(2).setScale(0, RoundingMode.HALF_UP);
        settlements.save(SellerSettlement.builder().marketplaceTransaction(transaction).sellerId(transaction.getSellerId())
                .grossAmount(transaction.getAmount()).platformFee(platformFee).netAmount(transaction.getAmount().subtract(platformFee))
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
