package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.client.UserServiceClient;
import com.haloticket.marketplace_service.entity.MarketplaceTransaction;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResaleReservationTest {
    @Test
    void reservesAnActiveListingForTheBuyerAndCreatesPendingTransaction() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        MarketplaceTransactionRepository transactions = mock(MarketplaceTransactionRepository.class);
        TicketListing listing = listing("seller-1", ListingStatus.ACTIVE);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(transactions.findByPaymentReference("reserve-key")).thenReturn(Optional.empty());
        when(transactions.save(any())).thenAnswer(invocation -> {
            MarketplaceTransaction transaction = invocation.getArgument(0);
            transaction.setId("transaction-1");
            return transaction;
        });

        var result = service(listings, transactions).reserveListing("buyer-1", "reserve-key", "listing-1");

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.RESERVED);
        assertThat(listing.getReservedBy()).isEqualTo("buyer-1");
        assertThat(listing.getReservedUntil()).isNotNull();
        assertThat(result.transactionId()).isEqualTo("transaction-1");
        assertThat(result.status()).isEqualTo(MarketplaceTransactionStatus.PENDING_PAYMENT);
    }

    @Test
    void preventsSellerFromBuyingTheirOwnListing() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing("seller-1", ListingStatus.ACTIVE)));

        assertThatThrownBy(() -> service(listings, mock(MarketplaceTransactionRepository.class))
                .reserveListing("seller-1", "reserve-key", "listing-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("BUYER_IS_SELLER");
    }

    @Test
    void rejectsAListingAlreadyReservedByAnotherBuyer() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        TicketListing listing = listing("seller-1", ListingStatus.RESERVED);
        listing.setReservedBy("buyer-2");
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service(listings, mock(MarketplaceTransactionRepository.class))
                .reserveListing("buyer-1", "reserve-key", "listing-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LISTING_NOT_AVAILABLE");
    }

    @Test
    void returnsTheBuyersExistingReservationWhenCheckoutIsRetried() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        MarketplaceTransactionRepository transactions = mock(MarketplaceTransactionRepository.class);
        TicketListing listing = listing("seller-1", ListingStatus.RESERVED);
        listing.setReservedBy("buyer-1");
        listing.setReservedUntil(Instant.now().plusSeconds(300));
        MarketplaceTransaction transaction = MarketplaceTransaction.builder().id("transaction-1")
                .ticketListing(listing).buyerId("buyer-1").sellerId("seller-1")
                .amount(listing.getPrice()).currency("VND")
                .status(MarketplaceTransactionStatus.PENDING_PAYMENT)
                .reservationExpiresAt(listing.getReservedUntil()).build();
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(transactions.findFirstByTicketListing_IdAndBuyerIdAndStatusIn(eq("listing-1"), eq("buyer-1"), any()))
                .thenReturn(Optional.of(transaction));

        var result = service(listings, transactions).reserveListing("buyer-1", "new-browser-key", "listing-1");

        assertThat(result.transactionId()).isEqualTo("transaction-1");
        verify(transactions, never()).save(any());
    }

    @Test
    void releasesAnExpiredReservationBackToTheMarketplace() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        MarketplaceTransactionRepository transactions = mock(MarketplaceTransactionRepository.class);
        TicketListing listing = listing("seller-1", ListingStatus.RESERVED);
        listing.setReservedBy("buyer-1");
        listing.setReservedUntil(Instant.now().minusSeconds(1));
        MarketplaceTransaction transaction = MarketplaceTransaction.builder().ticketListing(listing)
                .buyerId("buyer-1").sellerId("seller-1").amount(listing.getPrice())
                .status(MarketplaceTransactionStatus.PENDING_PAYMENT).build();
        when(listings.findByStatusAndReservedUntilBefore(eq(ListingStatus.RESERVED), any())).thenReturn(List.of(listing));
        when(transactions.findByTicketListing_IdAndStatus("listing-1", MarketplaceTransactionStatus.PENDING_PAYMENT))
                .thenReturn(Optional.of(transaction));

        service(listings, transactions).expireReservations();

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getReservedBy()).isNull();
        assertThat(transaction.getStatus()).isEqualTo(MarketplaceTransactionStatus.EXPIRED);
    }

    private static TicketListingService service(TicketListingRepository listings, MarketplaceTransactionRepository transactions) {
        return new TicketListingService(listings, mock(MarketplaceSagaRepository.class), mock(OutboxEventRepository.class),
                mock(TicketTransferRepository.class), transactions, mock(TicketServiceClient.class),
                mock(EventServiceClient.class), mock(UserServiceClient.class), new ObjectMapper());
    }

    private static TicketListing listing(String sellerId, ListingStatus status) {
        TicketListing listing = TicketListing.builder().ticketId("ticket-1").sellerId(sellerId)
                .price(new BigDecimal("120000")).status(status).sagaId("saga-1").build();
        listing.setId("listing-1");
        listing.setPublishedAt(Instant.now().minusSeconds(60));
        return listing;
    }
}
