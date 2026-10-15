package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.dto.request.GiftPolicyRequest;
import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.GiftPolicyResponse;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.entity.GiftPolicy;
import com.haloticket.event_service.entity.ResalePolicy;
import com.haloticket.event_service.entity.TransferPolicyOutboxEvent;
import com.haloticket.event_service.repository.TransferPolicyAuditRepository;
import com.haloticket.event_service.repository.TransferPolicyOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TransferPolicySupportTest {
    @Test void policyOutboxIncludesControlsAndLegacyRequestsLeaveControlsUnspecified() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var outbox = mock(TransferPolicyOutboxRepository.class);
        var support = new TransferPolicySupport(null, null, mock(TransferPolicyAuditRepository.class), outbox, mapper);
        var resale = ResalePolicyResponse.from(ResalePolicy.builder().eventId("e1")
                .allowedTicketTypeIds(Set.of()).version(0L).build());
        var gift = GiftPolicyResponse.from(GiftPolicy.builder().eventId("e1")
                .allowedTicketTypeIds(Set.of()).version(0L).build());
        support.record("e1", "RESALE", "owner", null, resale, 0L);
        support.record("e1", "GIFT", "owner", null, gift, 0L);
        var messages = ArgumentCaptor.forClass(TransferPolicyOutboxEvent.class);
        verify(outbox, times(2)).save(messages.capture());
        var resaleRules = mapper.readTree(messages.getAllValues().get(0).getPayload()).get("rules");
        assertThat(resaleRules.get("serviceFeePercent").decimalValue()).isEqualByComparingTo("5");
        assertThat(resaleRules.get("requireApproval").booleanValue()).isFalse();
        var giftRules = mapper.readTree(messages.getAllValues().get(1).getPayload()).get("rules");
        assertThat(giftRules.get("requireEkyc").booleanValue()).isTrue();
        assertThat(giftRules.get("maxGiftCount").intValue()).isEqualTo(3);
        var oldResale = mapper.readValue("{\"enabled\":true,\"minPricePercent\":80,\"maxPricePercent\":120}", ResalePolicyRequest.class);
        assertThat(oldResale.serviceFeePercent()).isNull();
        assertThat(oldResale.requireApproval()).isNull();
        var oldGift = mapper.readValue("{\"enabled\":true}", GiftPolicyRequest.class);
        assertThat(oldGift.requireEkyc()).isNull();
        assertThat(oldGift.maxGiftCount()).isNull();
    }
}
