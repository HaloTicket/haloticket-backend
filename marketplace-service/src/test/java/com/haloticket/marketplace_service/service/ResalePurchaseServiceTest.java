package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResalePurchaseServiceTest {
    @ParameterizedTest
    @CsvSource({"120000, 5.00, 6000, 114000", "100010, 5.00, 5001, 95009", "120000, 2.50, 3000, 117000", "100000, 2.555, 2555, 97445", "120000, 0, 0, 120000", "120000, NULL, 6000, 114000"})
    void settlementUsesTransactionFeeSnapshotAndRoundsVndOnce(String amount, String percent, String fee, String net) {
        Fixture fixture = new Fixture();
        fixture.transaction.setAmount(new BigDecimal(amount));
        fixture.transaction.setServiceFeePercent("NULL".equals(percent) ? null : new BigDecimal(percent));
        fixture.transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        fixture.transaction.setSagaId("purchase-saga");
        when(fixture.transactions.findBySagaId("purchase-saga")).thenReturn(Optional.of(fixture.transaction));
        when(fixture.sagas.findById("purchase-saga")).thenReturn(Optional.of(MarketplaceSaga.builder().state(SagaState.WAITING_TRANSFER).build()));
        when(fixture.transfers.findBySagaId("purchase-saga")).thenReturn(Optional.of(TicketTransfer.builder().ticketId("ticket-1").build()));

        fixture.service.handleOwnershipTransferred("purchase-saga");
        fixture.service.handleOwnershipTransferred("purchase-saga");

        verify(fixture.settlements, times(1)).save(argThat(settlement ->
                settlement.getGrossAmount().compareTo(new BigDecimal(amount)) == 0
                        && settlement.getPlatformFee().compareTo(new BigDecimal(fee)) == 0
                        && settlement.getNetAmount().compareTo(new BigDecimal(net)) == 0
                        && settlement.getStatus() == SettlementStatus.PENDING_SETTLEMENT));
    }

    @Test
    void timedOutPaidTransferRetriesSameMembersAndLateSuccessCompletesOnce() {
        Fixture fixture = new Fixture();
        fixture.listing.setMembers(new java.util.ArrayList<>(java.util.List.of(
                new TicketMemberSnapshot("ticket-1", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false),
                new TicketMemberSnapshot("ticket-2", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false))));
        fixture.transaction.setTicketIds(new java.util.LinkedHashSet<>(java.util.List.of("ticket-2")));
        fixture.transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        fixture.transaction.setSagaId("purchase-saga");
        MarketplaceSaga saga = MarketplaceSaga.builder().id("purchase-saga").type("RESALE_PURCHASE")
                .state(SagaState.WAITING_TRANSFER).retryCount(0).build();
        when(fixture.sagas.findById("purchase-saga")).thenReturn(Optional.of(saga));
        when(fixture.transactions.findBySagaId("purchase-saga")).thenReturn(Optional.of(fixture.transaction));
        when(fixture.transfers.findBySagaId("purchase-saga")).thenReturn(Optional.of(TicketTransfer.builder().ticketId("ticket-2").status(TransferStatus.PENDING).build()));

        fixture.service.retryTimedOutTransfer("purchase-saga");
        assertThat(saga.getState()).isEqualTo(SagaState.WAITING_TRANSFER);
        assertThat(fixture.transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.TRANSFERRING);
        verify(fixture.outbox).save(argThat(event -> event.getPayload().contains("\"operationId\":\"purchase-saga:TRANSFER\"")
                && event.getPayload().contains("\"ticketIds\":[\"ticket-2\"]")
                && event.getPayload().contains("\"lockTicketIds\":[\"ticket-1\",\"ticket-2\"]")));
        fixture.service.handleOwnershipTransferred("purchase-saga");
        fixture.service.handleOwnershipTransferred("purchase-saga");
        assertThat(fixture.transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.COMPLETED);
        assertThat(fixture.listing.remainingMembers()).extracting(TicketMemberSnapshot::getTicketId).containsExactly("ticket-1");
        verify(fixture.settlements, times(1)).save(any());
    }
    @Test
    void batchPurchaseTransfersExactSelectionAtUnitPrice() {
        Fixture fixture = new Fixture();
        fixture.listing.setMembers(new java.util.ArrayList<>(java.util.List.of(
                new TicketMemberSnapshot("ticket-1", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false),
                new TicketMemberSnapshot("ticket-2", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false),
                new TicketMemberSnapshot("ticket-3", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false))));
        fixture.transaction.setTicketIds(new java.util.LinkedHashSet<>(java.util.List.of("ticket-1", "ticket-3")));
        fixture.transaction.setAmount(new BigDecimal("240000"));
        fixture.transaction.setStatus(MarketplaceTransactionStatus.PAYMENT_PROCESSING);
        when(fixture.transactions.findById("tx-1")).thenReturn(Optional.of(fixture.transaction));

        fixture.service.handlePaymentCompleted("tx-1", "payment-1", new BigDecimal("240000"));

        verify(fixture.outbox).save(argThat(event -> event.getPayload().contains("\"ticketIds\":[\"ticket-1\",\"ticket-3\"]")
                && event.getPayload().contains("\"purchasePrice\":120000")));
        verify(fixture.transfers).save(argThat(transfer -> transfer.getMembers().size() == 2));
    }
    @Test
    void subsetCompletionOnlyMarksSelectedMemberSold() {
        Fixture fixture = new Fixture();
        fixture.listing.setMembers(new java.util.ArrayList<>(java.util.List.of(
                new TicketMemberSnapshot("ticket-1", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false),
                new TicketMemberSnapshot("ticket-2", "type", "show", "VIP", null, null, null, new BigDecimal("100000"), false))));
        fixture.transaction.setTicketIds(new java.util.LinkedHashSet<>(java.util.Set.of("ticket-1")));
        fixture.transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        fixture.transaction.setSagaId("purchase-saga");
        when(fixture.transactions.findBySagaId("purchase-saga")).thenReturn(Optional.of(fixture.transaction));
        when(fixture.transfers.findBySagaId("purchase-saga")).thenReturn(Optional.of(TicketTransfer.builder().ticketId("ticket-1").status(TransferStatus.PENDING).build()));
        when(fixture.sagas.findById("purchase-saga")).thenReturn(Optional.of(MarketplaceSaga.builder().state(SagaState.WAITING_TRANSFER).build()));

        fixture.service.handleOwnershipTransferred("purchase-saga");

        assertThat(fixture.listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(fixture.listing.remainingMembers()).extracting(TicketMemberSnapshot::getTicketId).containsExactly("ticket-2");
        assertThat(fixture.listing.getQuantity()).isEqualTo(1);
    }
    @Test
    void buyerCanReadPurchaseCompletionStatus() {
        Fixture fixture = new Fixture();
        fixture.transaction.setStatus(MarketplaceTransactionStatus.COMPLETED);
        fixture.transaction.setSagaId("purchase-saga");
        when(fixture.transactions.findById("tx-1")).thenReturn(Optional.of(fixture.transaction));

        var result = fixture.service.getStatus("buyer-1", "tx-1");

        assertThat(result.status()).isEqualTo(MarketplaceTransactionStatus.COMPLETED);
        assertThat(result.sagaId()).isEqualTo("purchase-saga");
    }

    @Test
    void startingPaymentAgainReturnsTheSamePayableTransaction() {
        Fixture fixture = new Fixture();
        fixture.transaction.setStatus(MarketplaceTransactionStatus.PAYMENT_PROCESSING);
        when(fixture.transactions.findById("tx-1")).thenReturn(Optional.of(fixture.transaction));

        var result = fixture.service.startPayment("buyer-1", "tx-1");

        assertThat(result.transactionId()).isEqualTo("tx-1");
        assertThat(result.amount()).isEqualByComparingTo("120000");
        assertThat(fixture.transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.PAYMENT_PROCESSING);
    }

    @Test
    void paidReservationStartsOwnershipTransferExactlyOnce() {
        Fixture fixture = new Fixture();
        fixture.transaction.setStatus(MarketplaceTransactionStatus.PAYMENT_PROCESSING);
        when(fixture.transactions.findById("tx-1")).thenReturn(Optional.of(fixture.transaction));
        when(fixture.sagas.findById(anyString())).thenReturn(Optional.empty());

        fixture.service.handlePaymentCompleted("tx-1", "payment-1", new BigDecimal("120000"));
        fixture.service.handlePaymentCompleted("tx-1", "payment-1", new BigDecimal("120000"));

        assertThat(fixture.transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.TRANSFERRING);
        assertThat(fixture.transaction.getSagaId()).isNotBlank();
        verify(fixture.outbox, times(1)).save(any());
        verify(fixture.transfers, times(1)).save(any());
    }

    @Test
    void completedTransferSellsListingAndCreatesPendingSettlement() {
        Fixture fixture = new Fixture();
        fixture.transaction.setStatus(MarketplaceTransactionStatus.TRANSFERRING);
        fixture.transaction.setSagaId("purchase-saga");
        TicketTransfer transfer = TicketTransfer.builder().id("transfer-1").transaction(fixture.transaction)
                .ticketId("ticket-1").fromUserId("seller-1").toUserId("buyer-1")
                .type(TransferType.RESALE).status(TransferStatus.PENDING).sagaId("purchase-saga").build();
        MarketplaceSaga saga = MarketplaceSaga.builder().id("purchase-saga").type("RESALE_PURCHASE")
                .ticketId("ticket-1").actorId("buyer-1").state(SagaState.WAITING_TRANSFER).step("TRANSFER_REQUESTED")
                .idempotencyKey("purchase:tx-1").requestHash("tx-1").retryCount(0).build();
        when(fixture.transactions.findBySagaId("purchase-saga")).thenReturn(Optional.of(fixture.transaction));
        when(fixture.transfers.findBySagaId("purchase-saga")).thenReturn(Optional.of(transfer));
        when(fixture.sagas.findById("purchase-saga")).thenReturn(Optional.of(saga));

        fixture.service.handleOwnershipTransferred("purchase-saga");

        assertThat(fixture.transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.COMPLETED);
        assertThat(fixture.listing.getStatus()).isEqualTo(ListingStatus.SOLD);
        verify(fixture.settlements).save(argThat(item -> item.getStatus() == SettlementStatus.PENDING_SETTLEMENT));
    }

    private static class Fixture {
        final MarketplaceTransactionRepository transactions = mock(MarketplaceTransactionRepository.class);
        final MarketplaceSagaRepository sagas = mock(MarketplaceSagaRepository.class);
        final TicketTransferRepository transfers = mock(TicketTransferRepository.class);
        final SellerSettlementRepository settlements = mock(SellerSettlementRepository.class);
        final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        final TicketListing listing = TicketListing.builder().id("listing-1").ticketId("ticket-1").sellerId("seller-1")
                .price(new BigDecimal("120000")).status(ListingStatus.RESERVED).reservedBy("buyer-1")
                .reservedUntil(Instant.now().plusSeconds(600)).sagaId("listing-saga").build();
        final MarketplaceTransaction transaction = MarketplaceTransaction.builder().id("tx-1").ticketListing(listing)
                .buyerId("buyer-1").sellerId("seller-1").amount(new BigDecimal("120000"))
                .status(MarketplaceTransactionStatus.PENDING_PAYMENT).reservationExpiresAt(Instant.now().plusSeconds(600)).build();
        final ResalePurchaseService service = new ResalePurchaseService(transactions, sagas, transfers, settlements, outbox, new ObjectMapper(), mock(EventTransferPolicyService.class));
    }
}
