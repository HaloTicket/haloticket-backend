package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResalePurchaseServiceTest {
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
        final ResalePurchaseService service = new ResalePurchaseService(transactions, sagas, transfers, settlements, outbox, new ObjectMapper());
    }
}
