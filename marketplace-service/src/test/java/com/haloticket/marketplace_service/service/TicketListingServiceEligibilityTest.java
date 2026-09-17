package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.client.TicketServiceClient;
import com.haloticket.marketplace_service.repository.MarketplaceSagaRepository;
import com.haloticket.marketplace_service.repository.OutboxEventRepository;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import com.haloticket.marketplace_service.repository.TicketTransferRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketListingServiceEligibilityTest {

    @Test
    void eligibilityIncludesTheCurrentResalePriceRangeAndPolicySnapshot() {
        TicketServiceClient ticketClient = mock(TicketServiceClient.class);
        EventServiceClient eventClient = mock(EventServiceClient.class);
        TicketListingService service = new TicketListingService(
                mock(TicketListingRepository.class),
                mock(MarketplaceSagaRepository.class),
                mock(OutboxEventRepository.class),
                mock(TicketTransferRepository.class),
                ticketClient,
                eventClient,
                new ObjectMapper()
        );
        Instant closesAt = Instant.parse("2026-09-20T09:26:00Z");
        when(ticketClient.getResaleEligibility("Bearer token", "ticket-1"))
                .thenReturn(new TicketServiceClient.ResaleEligibilityResponse(
                        "ticket-1", "event-1", true, null,
                        new BigDecimal("120000"), null, null, null, null, null));
        when(eventClient.getResalePolicy("Bearer token", "event-1"))
                .thenReturn(new EventServiceClient.ResalePolicyResponse(
                        true, new BigDecimal("80"), new BigDecimal("120"), closesAt, "terms-v3", 3L));

        TicketServiceClient.ResaleEligibilityResponse result = service.eligibility("Bearer token", "ticket-1");

        assertThat(result.eligible()).isTrue();
        assertThat(result.minPrice()).isEqualByComparingTo("96000");
        assertThat(result.maxPrice()).isEqualByComparingTo("144000");
        assertThat(result.policyVersion()).isEqualTo(3L);
        assertThat(result.closesAt()).isEqualTo(closesAt);
        assertThat(result.termsVersion()).isEqualTo("terms-v3");
    }
}
