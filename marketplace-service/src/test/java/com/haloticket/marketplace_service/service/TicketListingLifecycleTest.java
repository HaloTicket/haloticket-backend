package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.client.UserServiceClient;
import com.haloticket.marketplace_service.dto.ListingResponse;
import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.entity.OutboxEvent;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.SagaState;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TicketListingLifecycleTest {

    @Test
    void sellerCancellationHidesTheListingAndRequestsTicketRelease() throws Exception {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        MarketplaceSagaRepository sagas = mock(MarketplaceSagaRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        TicketListingService service = service(listings, sagas, outbox);
        TicketListing listing = listing("seller-1", ListingStatus.ACTIVE);
        MarketplaceSaga saga = MarketplaceSaga.builder().id("saga-1").ticketId("ticket-1")
                .actorId("seller-1").type("RESALE_LISTING").state(SagaState.COMPLETED).build();
        when(listings.findById("listing-1")).thenReturn(Optional.of(listing));
        when(sagas.findById("saga-1")).thenReturn(Optional.of(saga));

        invokeCancel(service, "seller-1", "listing-1");

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga.getLastError()).isEqualTo("SELLER_CANCELLED");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getTopic()).isEqualTo("haloticket.ticket.release.requested.v1");
        assertThat(event.getValue().getPayload()).contains("\"operationId\":\"saga-1\"");
    }

    @Test
    void anotherUserCannotCancelTheListing() {
        TicketListingRepository listings = mock(TicketListingRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        TicketListingService service = service(listings, mock(MarketplaceSagaRepository.class), outbox);
        when(listings.findById("listing-1")).thenReturn(Optional.of(listing("seller-1", ListingStatus.ACTIVE)));

        assertThatThrownBy(() -> invokeCancel(service, "seller-2", "listing-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verifyNoInteractions(outbox);
    }

    @Test
    void publicListingResponseDoesNotExposeSellerIdentity() {
        assertThat(Arrays.stream(ListingResponse.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("sellerId")
                .contains("eventId", "ticketTypeName", "section", "row", "seat", "ownedByCurrentUser");
    }

    @Test
    void listingMarksOwnershipWithoutExposingSellerIdentity() {
        TicketListing listing = listing("seller-1", ListingStatus.ACTIVE);

        assertThat(ListingResponse.from(listing, "seller-1").ownedByCurrentUser()).isTrue();
        assertThat(ListingResponse.from(listing, "buyer-1").ownedByCurrentUser()).isFalse();
    }

    @Test
    void oneTicketIsAlwaysRetailWhileMultiTicketListingHonoursSellerChoice() {
        assertThat(TicketListingService.retailAllowed(1, false)).isTrue();
        assertThat(TicketListingService.retailAllowed(2, true)).isTrue();
        assertThat(TicketListingService.retailAllowed(2, false)).isFalse();
    }

    private static TicketListingService service(TicketListingRepository listings, MarketplaceSagaRepository sagas, OutboxEventRepository outbox) {
        return new TicketListingService(listings, sagas, outbox, mock(TicketTransferRepository.class), mock(MarketplaceTransactionRepository.class),
                mock(TicketServiceClient.class), mock(EventServiceClient.class), mock(UserServiceClient.class), new ObjectMapper(),
                mock(EventTransferPolicyService.class));
    }

    private static TicketListing listing(String sellerId, ListingStatus status) {
        TicketListing listing = TicketListing.builder().ticketId("ticket-1").sellerId(sellerId)
                .price(new BigDecimal("120000")).status(status).sagaId("saga-1").build();
        listing.setId("listing-1");
        return listing;
    }

    private static void invokeCancel(TicketListingService service, String actorId, String listingId) throws Exception {
        try {
            service.getClass().getMethod("cancelListing", String.class, String.class).invoke(service, actorId, listingId);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }
}
