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

    @Test void omittedControlsUseDefaultsAndPreserveExistingValues() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(policyRepository.findByEventId("e1")).thenReturn(Optional.empty());
        when(policyRepository.save(any())).thenAnswer(invocation -> {
            ResalePolicy policy = invocation.getArgument(0);
            policy.setVersion(0L);
            return policy;
        });
        var request = new ResalePolicyRequest(true, BigDecimal.ZERO, new BigDecimal("120"),
                null, null, "ALL", Set.of(), null, null);
        var created = service.upsert("e1", request, "Bearer token");
        assertThat(created.serviceFeePercent()).isEqualByComparingTo("5");
        assertThat(created.requireApproval()).isFalse();
        ResalePolicy existing = savedPolicy("e1");
        existing.setServiceFeePercent(new BigDecimal("2.5"));
        existing.setRequireApproval(true);
        when(policyRepository.findByEventId("e1")).thenReturn(Optional.of(existing));
        var updated = service.upsert("e1", request, "Bearer token");
        assertThat(updated.serviceFeePercent()).isEqualByComparingTo("2.5");
        assertThat(updated.requireApproval()).isTrue();
    }

    @Test void savesExplicitControlsAndIncludesThemInPolicyEvent() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        when(policyRepository.findByEventId("e1")).thenReturn(Optional.of(savedPolicy("e1")));
        when(policyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        for (int fee : new int[]{0, 100}) {
            var request = new ResalePolicyRequest(true, BigDecimal.ZERO, new BigDecimal("120"),
                    null, null, "ALL", Set.of(), null, 0L, BigDecimal.valueOf(fee), fee == 0);
            var response = service.upsert("e1", request, "Bearer token");
            assertThat(response.serviceFeePercent()).isEqualByComparingTo(BigDecimal.valueOf(fee));
            assertThat(response.requireApproval()).isEqualTo(fee == 0);
            verify(support).record(eq("e1"), eq("RESALE"), eq("user-1"), any(), eq(response), eq(0L));
        }
    }

    @Test void invalidServiceFeesRejectBeforeSaving() {
        when(access.requireWrite("e1", "Bearer token")).thenReturn("user-1");
        for (String fee : new String[]{"-0.1", "100.1"}) {
            var request = new ResalePolicyRequest(true, BigDecimal.ZERO, new BigDecimal("120"),
                    null, null, "ALL", Set.of(), null, null, new BigDecimal(fee), false);
            assertThatThrownBy(() -> service.upsert("e1", request, "Bearer token"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(422));
        }
        verify(policyRepository, never()).save(any());
    }
}
