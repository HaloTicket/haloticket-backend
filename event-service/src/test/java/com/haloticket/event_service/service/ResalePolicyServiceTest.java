package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.entity.ResalePolicy;
import com.haloticket.event_service.repository.ResalePolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResalePolicyServiceTest {
    @Mock ResalePolicyRepository policyRepository;
    @Mock EventAccessService access;
    @Mock TransferPolicySupport support;
    @InjectMocks ResalePolicyService service;

    private ResalePolicy savedPolicy(String eventId) {
        ResalePolicy p = ResalePolicy.builder().eventId(eventId).enabled(true)
                .minPricePercent(new BigDecimal("80")).maxPricePercent(new BigDecimal("120"))
                .ticketTypeScope("ALL").allowedTicketTypeIds(Set.of()).version(0L).build();
        p.setCreatedAt(Instant.now()); p.setUpdatedAt(Instant.now());
        return p;
    }

    @Test void ownerCanUpsertNewPolicy() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(policyRepository.findByEventId("e1")).thenReturn(Optional.empty());
        ResalePolicy saved = savedPolicy("e1");
        when(policyRepository.save(any())).thenReturn(saved);
        var req = new ResalePolicyRequest(true, new BigDecimal("80"), new BigDecimal("120"),
                null, null, "ALL", Set.of(), null, null);
        ResalePolicyResponse res = service.upsert("e1", req, "Bearer token");
        assertThat(res.enabled()).isTrue();
        assertThat(res.minPricePercent()).isEqualByComparingTo("80");
        verify(support).record(eq("e1"), eq("RESALE"), eq("user-1"), isNull(), any(), eq(0L));
        verify(support, never()).version(any(), any()); // no version check on create
    }

    @Test void invalidMinGreaterThanMaxRejects422() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        var req = new ResalePolicyRequest(true, new BigDecimal("120"), new BigDecimal("80"),
                null, null, "ALL", Set.of(), null, null);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test void negativeMinRejects422() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        var req = new ResalePolicyRequest(true, new BigDecimal("-10"), new BigDecimal("100"),
                null, null, "ALL", Set.of(), null, null);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test void versionMismatchRejects409() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        ResalePolicy existing = savedPolicy("e1");
        existing.setVersion(2L);
        when(policyRepository.findByEventId("e1")).thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "POLICY_CHANGED"))
                .when(support).version(1L, 2L);
        var req = new ResalePolicyRequest(true, new BigDecimal("80"), new BigDecimal("120"),
                null, null, "ALL", Set.of(), null, 1L);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test void forbiddenRoleRejects403() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"))
                .when(access).requireWrite("e1", "Bearer token");
        var req = new ResalePolicyRequest(true, new BigDecimal("80"), new BigDecimal("120"),
                null, null, "ALL", Set.of(), null, null);
        assertThatThrownBy(() -> service.upsert("e1", req, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }
}
