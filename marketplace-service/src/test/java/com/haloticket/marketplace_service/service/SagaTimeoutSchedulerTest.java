package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.enums.SagaState;
import com.haloticket.marketplace_service.repository.MarketplaceSagaRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SagaTimeoutSchedulerTest {
    @Test
    void paidPurchaseTimeoutRetriesTransferWithoutReleasingOriginalListingLock() {
        MarketplaceSagaRepository sagas = mock(MarketplaceSagaRepository.class);
        TicketListingService listings = mock(TicketListingService.class);
        ResalePurchaseService purchases = mock(ResalePurchaseService.class);
        MarketplaceSaga saga = MarketplaceSaga.builder().id("purchase-saga").type("RESALE_PURCHASE")
                .state(SagaState.WAITING_TRANSFER).deadlineAt(Instant.now().minusSeconds(1)).build();
        when(sagas.findTop50ByStateInAndNextRetryAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(saga));

        new SagaTimeoutScheduler(sagas, listings, purchases).recoverExpiredSagas();

        verify(purchases).retryTimedOutTransfer("purchase-saga");
        verifyNoInteractions(listings);
    }
}
