package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.client.UserServiceClient;
import com.haloticket.marketplace_service.dto.CreateListingRequest;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SellerEkycGateTest {

    @Test
    void sellerMustCompleteEkycBeforeStartingAResaleListing() {
        UserServiceClient users = mock(UserServiceClient.class);
        MarketplaceSagaRepository sagas = mock(MarketplaceSagaRepository.class);
        when(users.getResaleEligibility("Bearer token"))
                .thenReturn(new UserServiceClient.ResaleSellerEligibility(false, "user-1", null, "EKYC_NOT_STARTED"));
        TicketListingService service = new TicketListingService(mock(TicketListingRepository.class), sagas,
                mock(OutboxEventRepository.class), mock(TicketTransferRepository.class), mock(MarketplaceTransactionRepository.class),
                mock(TicketServiceClient.class), mock(EventServiceClient.class), users, new ObjectMapper());

        assertThatThrownBy(() -> service.startResale("user-1", "Bearer token", "key-1",
                new CreateListingRequest("ticket-1", new BigDecimal("120000"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EKYC_REQUIRED");
        verify(sagas, never()).save(any());
    }
}
